from __future__ import annotations

import json
import math
import os
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from .event_rules import FoulRuleRun
from .schemas import EvidenceMetric
from .tracker import TrackObservation, TrackingRun

try:
    from PIL import Image, ImageDraw, ImageFont
except Exception:  # pragma: no cover - pose JSON remains available without Pillow.
    Image = None
    ImageDraw = None
    ImageFont = None


@dataclass(frozen=True)
class PoseRun:
    mode: str
    status: str
    estimator: str
    scope: str
    selected_track_ids: list[int]
    timestamp_ms: int | None
    pose_score: float
    features_path: str | None
    preview_path: str | None
    evidence_metrics: list[EvidenceMetric]
    note: str


def pose_settings() -> dict[str, str]:
    return {
        "mode": _env("AI_POSE_MODE", "local-rule-v1").lower(),
    }


def analyze_foul_pose(foul_rule_run: FoulRuleRun, tracking_run: TrackingRun, output_dir: Path) -> PoseRun:
    mode = pose_settings()["mode"]
    if mode == "disabled":
        return _empty_run(mode, "disabled", "Pose feature extraction is disabled.")

    selected = foul_rule_run.selected
    if selected is None:
        return _empty_run(mode, "skipped", "No foul candidate was selected for local pose analysis.")

    if mode == "mock":
        return _write_pose_run(
            output_dir,
            mode,
            "completed",
            "mock-local-pose",
            selected_track_ids=list(selected.track_ids),
            timestamp_ms=selected.timestamp_ms,
            crop_box=None,
            keypoints={},
            evidence_metrics=_mock_metrics(),
            note="Mock local pose features retained for demo stability.",
        )

    if mode != "local-rule-v1":
        return _write_pose_run(
            output_dir,
            mode,
            "fallback",
            "mock-local-pose",
            selected_track_ids=list(selected.track_ids),
            timestamp_ms=selected.timestamp_ms,
            crop_box=None,
            keypoints={},
            evidence_metrics=_mock_metrics(),
            note=f"Unknown AI_POSE_MODE={mode!r}; fallback local pose features were used.",
        )

    observations = [
        _nearest_observation(tracking_run, track_id, selected.timestamp_ms)
        for track_id in selected.track_ids
    ]
    if any(observation is None for observation in observations):
        return _write_pose_run(
            output_dir,
            mode,
            "fallback",
            "mock-local-pose",
            selected_track_ids=list(selected.track_ids),
            timestamp_ms=selected.timestamp_ms,
            crop_box=None,
            keypoints={},
            evidence_metrics=_mock_metrics(),
            note="Selected tracks had no local observations; fallback pose features were used.",
        )

    first, second = observations
    assert first is not None and second is not None
    crop_box = _expanded_union(first.box, second.box)
    overlap = _overlap_ratio(first.box, second.box)
    center_distance = _distance(_center(first.box), _center(second.box))
    limb_extension = _clamp(1.0 - center_distance / _float_env("AI_POSE_LIMB_DISTANCE_PX", 120.0))
    body_axis_delta = round(min(45.0, 10.0 + overlap * 35.0), 2)
    pose_score = round(_clamp(0.45 * overlap + 0.35 * limb_extension + 0.20 * (body_axis_delta / 45.0)), 3)

    keypoints = {
        str(track_id): _synthetic_keypoints(observation.box, toward=_center(other.box))
        for track_id, observation, other in [
            (selected.track_ids[0], first, second),
            (selected.track_ids[1], second, first),
        ]
    }
    metrics = [
        EvidenceMetric(
            name="pose_anomaly",
            value=pose_score,
            unit="score",
            description="Local candidate-pair pose anomaly proxy",
        ),
        EvidenceMetric(
            name="box_overlap_ratio",
            value=round(overlap, 3),
            unit="score",
            description="Overlap ratio between the selected players' local crops",
        ),
        EvidenceMetric(
            name="limb_extension",
            value=round(limb_extension, 3),
            unit="score",
            description="Proxy for extended limb/contact posture from local player boxes",
        ),
        EvidenceMetric(
            name="body_axis_delta",
            value=body_axis_delta,
            unit="deg",
            description="Proxy body-axis divergence inside the local crop",
        ),
    ]

    return _write_pose_run(
        output_dir,
        mode,
        "completed",
        "local-pose-rule-v1",
        selected_track_ids=list(selected.track_ids),
        timestamp_ms=selected.timestamp_ms,
        crop_box=crop_box,
        keypoints=keypoints,
        evidence_metrics=metrics,
        note="Local pose features were computed only for the selected foul candidate pair.",
    )


def _write_pose_run(
    output_dir: Path,
    mode: str,
    status: str,
    estimator: str,
    selected_track_ids: list[int],
    timestamp_ms: int | None,
    crop_box: list[float] | None,
    keypoints: dict[str, Any],
    evidence_metrics: list[EvidenceMetric],
    note: str,
) -> PoseRun:
    output_dir.mkdir(parents=True, exist_ok=True)
    features_path = output_dir / "pose_features.json"
    preview_path = output_dir / "pose_preview.png"
    pose_score = next((metric.value for metric in evidence_metrics if metric.name == "pose_anomaly"), 0.0)

    payload = {
        "mode": mode,
        "status": status,
        "estimator": estimator,
        "scope": "selected_foul_candidate_pair",
        "selectedTrackIds": selected_track_ids,
        "timestampMs": timestamp_ms,
        "poseScore": pose_score,
        "cropBox": crop_box,
        "keypoints": keypoints,
        "evidenceMetrics": [metric.model_dump() for metric in evidence_metrics],
        "note": note,
    }
    features_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    preview = _draw_pose_preview(preview_path, selected_track_ids, crop_box, keypoints, evidence_metrics)

    return PoseRun(
        mode=mode,
        status=status,
        estimator=estimator,
        scope="selected_foul_candidate_pair",
        selected_track_ids=selected_track_ids,
        timestamp_ms=timestamp_ms,
        pose_score=pose_score,
        features_path=str(features_path),
        preview_path=str(preview) if preview else None,
        evidence_metrics=evidence_metrics,
        note=note,
    )


def _empty_run(mode: str, status: str, note: str) -> PoseRun:
    return PoseRun(
        mode=mode,
        status=status,
        estimator="none",
        scope="selected_foul_candidate_pair",
        selected_track_ids=[],
        timestamp_ms=None,
        pose_score=0.0,
        features_path=None,
        preview_path=None,
        evidence_metrics=[],
        note=note,
    )


def _mock_metrics() -> list[EvidenceMetric]:
    return [
        EvidenceMetric(name="pose_anomaly", value=0.56, unit="score", description="Fallback local pose anomaly proxy"),
        EvidenceMetric(name="box_overlap_ratio", value=0.48, unit="score", description="Fallback local crop overlap"),
        EvidenceMetric(name="limb_extension", value=0.54, unit="score", description="Fallback limb-extension proxy"),
        EvidenceMetric(name="body_axis_delta", value=24.0, unit="deg", description="Fallback body-axis divergence"),
    ]


def _nearest_observation(tracking_run: TrackingRun, track_id: int, timestamp_ms: int) -> TrackObservation | None:
    track = tracking_run.tracks.get(track_id)
    if track is None or not track.observations:
        return None
    return min(track.observations, key=lambda observation: abs(observation.timestamp_ms - timestamp_ms))


def _expanded_union(first: list[float], second: list[float]) -> list[float]:
    x1 = min(first[0], second[0])
    y1 = min(first[1], second[1])
    x2 = max(first[2], second[2])
    y2 = max(first[3], second[3])
    width = x2 - x1
    height = y2 - y1
    margin = max(18.0, 0.12 * max(width, height))
    return [round(x1 - margin, 2), round(y1 - margin, 2), round(x2 + margin, 2), round(y2 + margin, 2)]


def _overlap_ratio(first: list[float], second: list[float]) -> float:
    x1 = max(first[0], second[0])
    y1 = max(first[1], second[1])
    x2 = min(first[2], second[2])
    y2 = min(first[3], second[3])
    intersection = max(0.0, x2 - x1) * max(0.0, y2 - y1)
    smaller_area = min(_area(first), _area(second))
    if smaller_area <= 0:
        return 0.0
    return _clamp(intersection / smaller_area)


def _synthetic_keypoints(box: list[float], toward: tuple[float, float]) -> dict[str, list[float]]:
    x1, y1, x2, y2 = box
    cx, cy = _center(box)
    direction = -1 if toward[0] < cx else 1
    shoulder_y = y1 + (y2 - y1) * 0.28
    hip_y = y1 + (y2 - y1) * 0.56
    knee_y = y1 + (y2 - y1) * 0.80
    lean = direction * (x2 - x1) * 0.10
    return {
        "head": [round(cx + lean * 0.3, 2), round(y1 + (y2 - y1) * 0.12, 2)],
        "leftShoulder": [round(cx - (x2 - x1) * 0.18 + lean, 2), round(shoulder_y, 2)],
        "rightShoulder": [round(cx + (x2 - x1) * 0.18 + lean, 2), round(shoulder_y, 2)],
        "leftHip": [round(cx - (x2 - x1) * 0.14, 2), round(hip_y, 2)],
        "rightHip": [round(cx + (x2 - x1) * 0.14, 2), round(hip_y, 2)],
        "leftKnee": [round(cx - (x2 - x1) * 0.12, 2), round(knee_y, 2)],
        "rightKnee": [round(cx + (x2 - x1) * 0.12, 2), round(knee_y, 2)],
    }


def _draw_pose_preview(
    path: Path,
    selected_track_ids: list[int],
    crop_box: list[float] | None,
    keypoints: dict[str, Any],
    evidence_metrics: list[EvidenceMetric],
) -> Path | None:
    if Image is None:
        return None

    image = Image.new("RGB", (640, 420), "#f6f7f9")
    draw = ImageDraw.Draw(image)
    font = ImageFont.load_default()
    draw.rectangle([0, 0, 640, 56], fill="#1f2937")
    draw.text((24, 20), "Local pose features", fill="white", font=font)

    scale = 2.4
    offset_x = 80.0
    offset_y = 82.0
    if crop_box:
        crop_w = max(1.0, crop_box[2] - crop_box[0])
        crop_h = max(1.0, crop_box[3] - crop_box[1])
        scale = min(3.0, 420.0 / crop_w, 260.0 / crop_h)
        offset_x = 70.0 - crop_box[0] * scale
        offset_y = 86.0 - crop_box[1] * scale
        draw.rectangle(
            [_tx(crop_box[0], scale, offset_x), _ty(crop_box[1], scale, offset_y), _tx(crop_box[2], scale, offset_x), _ty(crop_box[3], scale, offset_y)],
            outline="#94a3b8",
            width=2,
        )

    colors = ["#2563eb", "#dc2626"]
    for index, track_id in enumerate(selected_track_ids):
        points = keypoints.get(str(track_id), {})
        color = colors[index % len(colors)]
        _draw_skeleton(draw, points, scale, offset_x, offset_y, color)
        if points.get("head"):
            hx, hy = points["head"]
            draw.text((_tx(hx, scale, offset_x) + 6, _ty(hy, scale, offset_y) - 8), f"ID {track_id}", fill=color, font=font)

    evidence_y = 318
    draw.rectangle([360, evidence_y - 20, 620, 398], fill="#ffffff", outline="#cbd5e1")
    for line_index, metric in enumerate(evidence_metrics[:4]):
        y = evidence_y + line_index * 20
        draw.text((374, y), f"{metric.name}: {metric.value} {metric.unit or ''}", fill="#334155", font=font)

    image.save(path)
    return path


def _draw_skeleton(draw: Any, points: dict[str, list[float]], scale: float, offset_x: float, offset_y: float, color: str) -> None:
    limbs = [
        ("head", "leftShoulder"),
        ("head", "rightShoulder"),
        ("leftShoulder", "rightShoulder"),
        ("leftShoulder", "leftHip"),
        ("rightShoulder", "rightHip"),
        ("leftHip", "rightHip"),
        ("leftHip", "leftKnee"),
        ("rightHip", "rightKnee"),
    ]
    for first, second in limbs:
        if first not in points or second not in points:
            continue
        draw.line([_point(points[first], scale, offset_x, offset_y), _point(points[second], scale, offset_x, offset_y)], fill=color, width=3)
    for point in points.values():
        x, y = _point(point, scale, offset_x, offset_y)
        draw.ellipse([x - 4, y - 4, x + 4, y + 4], fill=color)


def _point(point: list[float], scale: float, offset_x: float, offset_y: float) -> tuple[float, float]:
    return _tx(point[0], scale, offset_x), _ty(point[1], scale, offset_y)


def _tx(value: float, scale: float, offset_x: float) -> float:
    return value * scale + offset_x


def _ty(value: float, scale: float, offset_y: float) -> float:
    return value * scale + offset_y


def _area(box: list[float]) -> float:
    return max(0.0, box[2] - box[0]) * max(0.0, box[3] - box[1])


def _center(box: list[float]) -> tuple[float, float]:
    return (box[0] + box[2]) / 2, (box[1] + box[3]) / 2


def _distance(first: tuple[float, float], second: tuple[float, float]) -> float:
    return math.hypot(first[0] - second[0], first[1] - second[1])


def _clamp(value: float, minimum: float = 0.0, maximum: float = 1.0) -> float:
    return max(minimum, min(maximum, value))


def _env(name: str, default: str) -> str:
    value = os.getenv(name)
    return value.strip() if value and value.strip() else default


def _float_env(name: str, default: float) -> float:
    try:
        return float(_env(name, str(default)))
    except ValueError:
        return default
