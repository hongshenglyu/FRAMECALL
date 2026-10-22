from __future__ import annotations

import json
import os
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from .detector import DetectorRun
from .schemas import TrackBox

try:
    from PIL import Image, ImageDraw, ImageFont
except Exception:  # pragma: no cover - tracking JSON remains available without Pillow.
    Image = None
    ImageDraw = None
    ImageFont = None


@dataclass(frozen=True)
class TrackObservation:
    timestamp_ms: int
    frame_index: int
    box: list[float]
    confidence: float


@dataclass(frozen=True)
class TrackState:
    track_id: int
    label: str
    team: str | None
    observations: list[TrackObservation]


@dataclass(frozen=True)
class TrackingRun:
    mode: str
    status: str
    tracker: str
    track_count: int
    observation_count: int
    id_switches: int
    idf1: float
    fps: float
    tracks_path: str | None
    preview_path: str | None
    note: str
    tracks: dict[int, TrackState]


def tracker_settings() -> dict[str, str]:
    return {
        "mode": _env("AI_TRACKER_MODE", "mock").lower(),
    }


def run_tracker(detector_run: DetectorRun, output_dir: Path) -> TrackingRun:
    mode = tracker_settings()["mode"]
    if mode == "mock":
        return _write_mock_tracks(output_dir, "mock", "completed", "mock-bytetrack", "Mock tracking produced stable player IDs.")

    if mode != "bytetrack":
        return _write_mock_tracks(
            output_dir,
            mode,
            "fallback",
            "mock-bytetrack",
            f"Unknown AI_TRACKER_MODE={mode!r}; mock tracking remains enabled.",
        )

    if detector_run.status != "completed" or not detector_run.detections_path:
        return _write_mock_tracks(
            output_dir,
            "bytetrack",
            "fallback",
            "mock-bytetrack",
            "ByteTrack needs completed YOLO detections; mock tracks were generated for the demo contract.",
        )

    return _write_detection_seeded_tracks(detector_run, output_dir)


def track_boxes_for(tracking_run: TrackingRun, track_ids: list[int], timestamp_ms: int, offset: int) -> list[TrackBox]:
    boxes: list[TrackBox] = []
    for index, track_id in enumerate(track_ids):
        track = tracking_run.tracks.get(track_id)
        observation = _nearest_observation(track, timestamp_ms) if track else None
        if observation is None:
            fallback = _fallback_box(index, offset)
            boxes.append(
                TrackBox(
                    trackId=track_id,
                    label="player",
                    team="A" if index == 0 else "B",
                    box=fallback,
                    confidence=round(0.82 - index * 0.04, 3),
                )
            )
            continue

        boxes.append(
            TrackBox(
                trackId=track.track_id,
                label=track.label,
                team=track.team,
                box=observation.box,
                confidence=observation.confidence,
            )
        )
    return boxes


def _write_detection_seeded_tracks(detector_run: DetectorRun, output_dir: Path) -> TrackingRun:
    started = time.perf_counter()
    try:
        payload = json.loads(Path(detector_run.detections_path or "").read_text(encoding="utf-8"))
        frames = payload.get("frames", [])
    except Exception as exc:
        return _write_mock_tracks(
            output_dir,
            "bytetrack",
            "fallback",
            "mock-bytetrack",
            f"Could not read YOLO detections for ByteTrack handoff: {exc}",
        )

    tracks: dict[int, TrackState] = {}
    next_track_id = 1
    for frame in frames:
        timestamp_ms = int(frame.get("frameIndex", 0)) * 40
        for detection in frame.get("detections", []):
            if str(detection.get("label", "")).lower() != "person":
                continue
            track_id = next_track_id
            next_track_id += 1
            tracks[track_id] = TrackState(
                track_id=track_id,
                label="player",
                team=None,
                observations=[
                    TrackObservation(
                        timestamp_ms=timestamp_ms,
                        frame_index=int(frame.get("frameIndex", 0)),
                        box=[float(value) for value in detection.get("box", [])],
                        confidence=float(detection.get("confidence", 0.0)),
                    )
                ],
            )

    elapsed = max(0.001, time.perf_counter() - started)
    note = "YOLO detections were normalized into the tracking contract; replace this adapter with real ByteTrack association next."
    return _write_tracks(output_dir, "bytetrack", "adapter_pending", "bytetrack-adapter", tracks, round(len(frames) / elapsed, 2), note)


def _write_mock_tracks(output_dir: Path, mode: str, status: str, tracker: str, note: str) -> TrackingRun:
    tracks = _mock_tracks()
    return _write_tracks(output_dir, mode, status, tracker, tracks, 0.0, note)


def _write_tracks(
    output_dir: Path,
    mode: str,
    status: str,
    tracker: str,
    tracks: dict[int, TrackState],
    fps: float,
    note: str,
) -> TrackingRun:
    output_dir.mkdir(parents=True, exist_ok=True)
    tracks_path = output_dir / "tracks.json"
    preview_path = output_dir / "tracks_preview.png"

    observation_count = sum(len(track.observations) for track in tracks.values())
    payload = {
        "mode": mode,
        "status": status,
        "tracker": tracker,
        "trackCount": len(tracks),
        "observationCount": observation_count,
        "tracks": [_track_to_json(track) for track in tracks.values()],
        "note": note,
    }
    tracks_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    preview = _draw_tracks_preview(preview_path, tracks)

    return TrackingRun(
        mode=mode,
        status=status,
        tracker=tracker,
        track_count=len(tracks),
        observation_count=observation_count,
        id_switches=0,
        idf1=0.0,
        fps=fps,
        tracks_path=str(tracks_path),
        preview_path=str(preview) if preview else None,
        note=note,
        tracks=tracks,
    )


def _mock_tracks() -> dict[int, TrackState]:
    timestamps = [9400, 12400, 15400, 25750, 28750, 31750, 46200, 49200, 52200]
    team_by_id = {7: "A", 9: "A", 11: "A", 12: "B", 3: "B"}
    contact_offsets = {
        7: {1: (46.0, 0.0), 2: (50.0, 0.0)},
        12: {1: (-34.0, 0.0), 2: (-28.0, 0.0)},
    }
    tracks: dict[int, TrackState] = {}
    for order, track_id in enumerate([7, 12, 9, 11, 3]):
        observations: list[TrackObservation] = []
        for frame_index, timestamp_ms in enumerate(timestamps):
            base_x = 110 + order * 95 + frame_index * (3 + order)
            base_y = 120 + (order % 2) * 32 + frame_index * 1.6
            offset_x, offset_y = contact_offsets.get(track_id, {}).get(frame_index, (0.0, 0.0))
            base_x += offset_x
            base_y += offset_y
            observations.append(
                TrackObservation(
                    timestamp_ms=timestamp_ms,
                    frame_index=frame_index * 75,
                    box=[round(base_x, 2), round(base_y, 2), round(base_x + 80, 2), round(base_y + 180, 2)],
                    confidence=round(0.88 - order * 0.025, 3),
                )
            )
        tracks[track_id] = TrackState(
            track_id=track_id,
            label="player",
            team=team_by_id.get(track_id),
            observations=observations,
        )
    return tracks


def _nearest_observation(track: TrackState | None, timestamp_ms: int) -> TrackObservation | None:
    if track is None or not track.observations:
        return None
    return min(track.observations, key=lambda observation: abs(observation.timestamp_ms - timestamp_ms))


def _fallback_box(index: int, offset: int) -> list[float]:
    x = 120 + 170 * index + 23 * offset
    y = 120 + 30 * index
    return [x, y, x + 80, y + 180]


def _track_to_json(track: TrackState) -> dict[str, Any]:
    return {
        "trackId": track.track_id,
        "label": track.label,
        "team": track.team,
        "observations": [
            {
                "timestampMs": observation.timestamp_ms,
                "frameIndex": observation.frame_index,
                "box": observation.box,
                "confidence": observation.confidence,
            }
            for observation in track.observations
        ],
    }


def _draw_tracks_preview(path: Path, tracks: dict[int, TrackState]) -> Path | None:
    if Image is None:
        return None

    image = Image.new("RGB", (960, 540), "#f6f7f9")
    draw = ImageDraw.Draw(image)
    font = ImageFont.load_default()
    draw.rectangle([48, 72, 912, 492], outline="#2f7d32", width=4)
    draw.line([480, 72, 480, 492], fill="#94a3b8", width=2)
    draw.text((24, 22), "Track preview", fill="#111827", font=font)

    colors = ["#2563eb", "#dc2626", "#0f766e", "#7c3aed", "#ea580c"]
    for index, track in enumerate(tracks.values()):
        color = colors[index % len(colors)]
        centers: list[tuple[float, float]] = []
        for observation in track.observations:
            x1, y1, x2, y2 = observation.box
            centers.append(((x1 + x2) / 2, (y1 + y2) / 2))
        if len(centers) >= 2:
            draw.line([coord for point in centers for coord in point], fill=color, width=3)
        if centers:
            cx, cy = centers[-1]
            draw.ellipse([cx - 5, cy - 5, cx + 5, cy + 5], fill=color)
            draw.text((cx + 8, cy - 8), f"ID {track.track_id}", fill=color, font=font)

    image.save(path)
    return path


def _env(name: str, default: str) -> str:
    value = os.getenv(name)
    return value.strip() if value and value.strip() else default
