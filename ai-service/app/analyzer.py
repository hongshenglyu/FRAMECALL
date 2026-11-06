from __future__ import annotations

import json
import math
import time
from pathlib import Path
from typing import Callable

from .detector import DetectorRun, run_detector
from .event_rules import FoulRuleRun, score_foul_candidates
from .pose import PoseRun, analyze_foul_pose
from .schemas import (
    AnalysisRequest,
    AnalysisResult,
    DisputeEventResult,
    EventType,
    EvidenceMetric,
    ProgressUpdate,
    RiskLevel,
    Sensitivity,
    TrackBox,
)
from .tracker import TrackingRun, run_tracker, track_boxes_for
from .security import send_progress

try:
    from PIL import Image, ImageDraw, ImageFont
except Exception:  # pragma: no cover - allows JSON-only fallback when Pillow is absent.
    Image = None
    ImageDraw = None
    ImageFont = None


CancelChecker = Callable[[int], bool]


def run_analysis(request: AnalysisRequest, cancel_checker: CancelChecker | None = None) -> AnalysisResult:
    """Create a deterministic analysis result that mirrors the production contract.

    This is intentionally a replaceable analyzer. The system can run end-to-end
    before model weights are ready; later, this function is the seam where
    YOLO, ByteTrack, pose estimation, and calibrated event scoring are inserted.
    """

    task_dir = Path(request.output_dir)
    events_dir = task_dir / "events"
    events_dir.mkdir(parents=True, exist_ok=True)

    def cancelled() -> bool:
        return bool(cancel_checker and cancel_checker(request.task_id))

    _progress(request, "EXTRACTING", 8, "Preparing video workspace")
    _sleep_or_cancel(request.task_id, cancelled)
    _assert_not_cancelled(cancelled)

    _progress(request, "DETECTING", 24, "Running detector on sampled frames")
    detector_run = run_detector(Path(request.video_path), task_dir / "detections")
    _progress(request, "DETECTING", 38, detector_run.note)
    _sleep_or_cancel(request.task_id, cancelled)
    _assert_not_cancelled(cancelled)

    _progress(request, "TRACKING", 54, "Linking players into multi-object tracks")
    tracking_run = run_tracker(detector_run, task_dir / "tracks")
    _progress(request, "TRACKING", 64, tracking_run.note)
    _sleep_or_cancel(request.task_id, cancelled)
    _assert_not_cancelled(cancelled)

    _progress(request, "EVENT_ANALYSIS", 72, "Scoring foul, handball, and offside candidates")
    foul_rule_run = score_foul_candidates(tracking_run, task_dir / "rules")
    pose_run = analyze_foul_pose(foul_rule_run, tracking_run, task_dir / "pose")
    events = _build_events(request, events_dir, tracking_run, foul_rule_run, pose_run)
    _assert_not_cancelled(cancelled)

    result_path = task_dir / "analysis_result.json"
    result = AnalysisResult(
        taskId=request.task_id,
        status="SUCCEEDED",
        stage="COMPLETED",
        progressPercent=100,
        resultJsonPath=str(result_path),
        events=events,
        metrics=_build_metrics(detector_run, tracking_run, foul_rule_run, pose_run),
        errorMessage=None,
    )
    result_path.write_text(result.model_dump_json(by_alias=True, indent=2), encoding="utf-8")
    _progress(request, "COMPLETED", 100, "Analysis result generated")
    return result


def _build_events(
    request: AnalysisRequest,
    events_dir: Path,
    tracking_run: TrackingRun,
    foul_rule_run: FoulRuleRun,
    pose_run: PoseRun,
) -> list[DisputeEventResult]:
    sensitivity_factor = {
        Sensitivity.HIGH_RECALL: 1.15,
        Sensitivity.BALANCED: 1.0,
        Sensitivity.LOW_FALSE_POSITIVE: 0.82,
    }[request.sensitivity]

    specs = [
        (EventType.FOUL, 12400, [7, 12], "Contact duel detected near the ball"),
        (EventType.HANDBALL, 28750, [9], "Ball approaches upper-limb region"),
        (EventType.OFFSIDE, 49200, [11, 3], "Attacker position should be reviewed against defensive line"),
    ]

    events: list[DisputeEventResult] = []
    for index, (event_type, timestamp_ms, track_ids, summary) in enumerate(specs, start=1):
        if event_type not in request.enabled_events:
            continue

        event_dir = events_dir / f"{index:02d}_{event_type.value.lower()}"
        event_dir.mkdir(parents=True, exist_ok=True)
        keyframe_path = event_dir / "keyframe.png"
        clip_path = event_dir / "clip_placeholder.txt"

        risk_value = min(0.96, (0.58 + index * 0.09) * sensitivity_factor)
        metrics = _metrics_for(event_type, risk_value)
        if event_type == EventType.FOUL and foul_rule_run.selected is not None:
            candidate = foul_rule_run.selected
            timestamp_ms = candidate.timestamp_ms
            track_ids = list(candidate.track_ids)
            summary = candidate.summary
            pose_bonus = 0.12 * pose_run.pose_score if pose_run.status in {"completed", "fallback"} else 0.0
            fused_confidence = max(candidate.confidence, candidate.confidence * 0.88 + pose_bonus)
            risk_value = min(0.96, fused_confidence * sensitivity_factor)
            metrics = candidate.evidence_metrics + pose_run.evidence_metrics

        risk_level = _risk_level(risk_value)
        boxes = track_boxes_for(tracking_run, track_ids, timestamp_ms, index)
        _draw_keyframe(keyframe_path, event_type, risk_level, timestamp_ms, track_ids, metrics, boxes)
        clip_path.write_text(
            "This placeholder marks the event review window. "
            "Install FFmpeg/OpenCV to cut an actual event clip from the uploaded video.\n",
            encoding="utf-8",
        )

        events.append(
            DisputeEventResult(
                externalId=f"T{request.task_id}-E{index:02d}",
                eventType=event_type,
                riskLevel=risk_level,
                timestampMs=timestamp_ms,
                windowStartMs=max(0, timestamp_ms - 3000),
                windowEndMs=timestamp_ms + 3000,
                involvedTrackIds=track_ids,
                summary=summary,
                evidenceMetrics=metrics,
                trackBoxes=boxes,
                keyframePath=str(keyframe_path),
                clipPath=str(clip_path),
                confidence=round(risk_value, 3),
            )
        )
    return events


def _build_metrics(
    detector_run: DetectorRun,
    tracking_run: TrackingRun,
    foul_rule_run: FoulRuleRun,
    pose_run: PoseRun,
) -> dict[str, object]:
    selected = foul_rule_run.selected
    return {
        "detector": {
            "mode": detector_run.mode,
            "status": detector_run.status,
            "model": detector_run.model,
            "sampledFrames": detector_run.sampled_frames,
            "detections": detector_run.detection_count,
            "detectionsJsonPath": detector_run.detections_path,
            "previewPath": detector_run.preview_path,
            "mAP50": 0.0,
            "precision": 0.0,
            "recall": 0.0,
            "fps": detector_run.fps,
            "note": detector_run.note,
        },
        "tracking": {
            "mode": tracking_run.mode,
            "status": tracking_run.status,
            "tracker": tracking_run.tracker,
            "trackCount": tracking_run.track_count,
            "observationCount": tracking_run.observation_count,
            "tracksJsonPath": tracking_run.tracks_path,
            "previewPath": tracking_run.preview_path,
            "idf1": tracking_run.idf1,
            "idSwitches": tracking_run.id_switches,
            "fps": tracking_run.fps,
            "note": tracking_run.note,
        },
        "foulCandidate": {
            "mode": foul_rule_run.mode,
            "status": foul_rule_run.status,
            "scorer": foul_rule_run.scorer,
            "candidateCount": foul_rule_run.candidate_count,
            "rulesJsonPath": foul_rule_run.rules_path,
            "selectedTrackIds": list(selected.track_ids) if selected else [],
            "selectedTimestampMs": selected.timestamp_ms if selected else None,
            "score": selected.score if selected else 0.0,
            "minDistancePx": selected.min_distance_px if selected else None,
            "relativeVelocityDelta": selected.relative_velocity_delta if selected else None,
            "closingSpeed": selected.closing_speed if selected else None,
            "recall": 0.0,
            "falsePositivesPer3Min": 0.0,
            "keyframeErrorFrames": 0.0,
            "note": foul_rule_run.note,
        },
        "poseEstimation": {
            "mode": pose_run.mode,
            "status": pose_run.status,
            "estimator": pose_run.estimator,
            "scope": pose_run.scope,
            "selectedTrackIds": pose_run.selected_track_ids,
            "timestampMs": pose_run.timestamp_ms,
            "poseScore": pose_run.pose_score,
            "featuresJsonPath": pose_run.features_path,
            "previewPath": pose_run.preview_path,
            "note": pose_run.note,
        },
    }


def _metrics_for(event_type: EventType, risk_value: float) -> list[EvidenceMetric]:
    if event_type == EventType.FOUL:
        return [
            EvidenceMetric(name="proximity", value=round(0.82 * risk_value, 3), unit="score", description="Player pair distance compression"),
            EvidenceMetric(name="velocity_delta", value=round(1.45 * risk_value, 3), unit="m/s", description="Relative speed change near contact"),
            EvidenceMetric(name="pose_anomaly", value=round(0.74 * risk_value, 3), unit="score", description="Skeleton instability around contact frame"),
        ]
    if event_type == EventType.HANDBALL:
        return [
            EvidenceMetric(name="ball_upper_limb_distance", value=round(38 * (1.1 - risk_value), 2), unit="px", description="Distance from ball to upper-limb region"),
            EvidenceMetric(name="ball_direction_change", value=round(22 * risk_value, 2), unit="deg", description="Ball trajectory bend near body"),
        ]
    return [
        EvidenceMetric(name="line_margin", value=round(0.04 * (1.1 - risk_value), 3), unit="pitch", description="Attacker margin around review line"),
        EvidenceMetric(name="calibration_confidence", value=round(0.78 * risk_value, 3), unit="score", description="Homography/keypoint confidence proxy"),
    ]


def _draw_keyframe(
    path: Path,
    event_type: EventType,
    risk_level: RiskLevel,
    timestamp_ms: int,
    track_ids: list[int],
    metrics: list[EvidenceMetric],
    boxes: list[TrackBox],
) -> None:
    if Image is None:
        path.with_suffix(".json").write_text(json.dumps({"eventType": event_type.value, "risk": risk_level.value}), encoding="utf-8")
        return

    image = Image.new("RGB", (960, 540), "#f6f7f9")
    draw = ImageDraw.Draw(image)
    font = ImageFont.load_default()

    draw.rectangle([0, 0, 960, 72], fill="#1f2937")
    draw.text((24, 22), f"{event_type.value}  {risk_level.value}  @ {timestamp_ms / 1000:.2f}s", fill="white", font=font)
    draw.rectangle([48, 96, 912, 488], outline="#2f7d32", width=4)
    draw.line([48, 292, 912, 292], fill="#94a3b8", width=2)
    draw.ellipse([730, 222, 750, 242], fill="#111827")

    colors = ["#2563eb", "#dc2626", "#0f766e"]
    for idx, box in enumerate(boxes):
        color = colors[idx % len(colors)]
        draw.rectangle(box.box, outline=color, width=4)
        draw.text((box.box[0], box.box[1] - 18), f"ID {box.track_id}", fill=color, font=font)
        cx = (box.box[0] + box.box[2]) / 2
        cy = (box.box[1] + box.box[3]) / 2
        draw.line([cx, box.box[1] + 30, cx - 24, cy], fill=color, width=3)
        draw.line([cx, box.box[1] + 30, cx + 24, cy], fill=color, width=3)
        draw.line([cx, cy, cx - 18, box.box[3] - 14], fill=color, width=3)
        draw.line([cx, cy, cx + 22, box.box[3] - 18], fill=color, width=3)

    evidence_y = 390
    draw.rectangle([590, evidence_y - 16, 900, 474], fill="#ffffff", outline="#cbd5e1")
    draw.text((606, evidence_y), "Evidence", fill="#111827", font=font)
    for line_index, metric in enumerate(metrics[:3], start=1):
        y = evidence_y + line_index * 24
        draw.text((606, y), f"{metric.name}: {metric.value} {metric.unit or ''}", fill="#334155", font=font)

    draw.text((24, 508), f"Involved tracks: {', '.join(map(str, track_ids))}", fill="#475569", font=font)
    image.save(path)


def _risk_level(score: float) -> RiskLevel:
    if math.isnan(score):
        return RiskLevel.UNCERTAIN
    if score >= 0.78:
        return RiskLevel.HIGH
    if score >= 0.58:
        return RiskLevel.MEDIUM
    if score >= 0.38:
        return RiskLevel.LOW
    return RiskLevel.UNCERTAIN


def _progress(request: AnalysisRequest, stage: str, percent: int, message: str) -> None:
    update = ProgressUpdate(taskId=request.task_id, stage=stage, progressPercent=percent, message=message)
    send_progress(request, update)


def _sleep_or_cancel(task_id: int, cancelled: Callable[[], bool]) -> None:
    for _ in range(3):
        if cancelled():
            break
        time.sleep(0.05)


def _assert_not_cancelled(cancelled: Callable[[], bool]) -> None:
    if cancelled():
        raise RuntimeError("Task was cancelled before analysis completed")
