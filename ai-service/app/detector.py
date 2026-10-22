from __future__ import annotations

import json
import os
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any


@dataclass(frozen=True)
class DetectorRun:
    mode: str
    status: str
    model: str
    sampled_frames: int
    detection_count: int
    fps: float
    detections_path: str | None
    preview_path: str | None
    note: str


def detector_settings() -> dict[str, str]:
    return {
        "mode": _env("AI_ANALYZER_MODE", "mock").lower(),
        "model": _env("AI_YOLO_MODEL", "models/yolo/yolov8n.pt"),
        "allowDownload": _env("AI_YOLO_ALLOW_DOWNLOAD", "false").lower(),
    }


def run_detector(video_path: Path, output_dir: Path) -> DetectorRun:
    mode = detector_settings()["mode"]
    if mode == "mock":
        return DetectorRun(
            mode="mock",
            status="disabled",
            model="mock-yolo-small",
            sampled_frames=0,
            detection_count=0,
            fps=0.0,
            detections_path=None,
            preview_path=None,
            note="Mock analyzer mode is active. Set AI_ANALYZER_MODE=yolo to run optional YOLO detection.",
        )

    if mode != "yolo":
        return _fallback(mode, "", f"Unknown AI_ANALYZER_MODE={mode!r}; mock events remain enabled.")

    return _run_yolo(video_path, output_dir)


def _run_yolo(video_path: Path, output_dir: Path) -> DetectorRun:
    model_ref = _env("AI_YOLO_MODEL", "models/yolo/yolov8n.pt")
    allow_download = _env("AI_YOLO_ALLOW_DOWNLOAD", "false").lower() == "true"
    confidence = _float_env("AI_YOLO_CONF", 0.25)
    max_frames = max(1, _int_env("AI_YOLO_MAX_FRAMES", 12))
    stride = max(1, _int_env("AI_YOLO_STRIDE", 15))
    class_filter = _class_filter()

    if not video_path.exists():
        return _fallback("yolo", model_ref, f"Video file does not exist: {video_path}")

    if not _model_available(model_ref, allow_download):
        return _fallback(
            "yolo",
            model_ref,
            "YOLO model weights were not found. Put weights at AI_YOLO_MODEL or set AI_YOLO_ALLOW_DOWNLOAD=true.",
        )

    try:
        from PIL import Image
        from ultralytics import YOLO
    except Exception as exc:
        return _fallback("yolo", model_ref, f"YOLO dependencies are not installed: {exc}")

    output_dir.mkdir(parents=True, exist_ok=True)
    detections_path = output_dir / "detections.json"
    preview_path = output_dir / "detections_preview.jpg"

    started = time.perf_counter()
    frames: list[dict[str, Any]] = []
    detection_count = 0
    preview_saved = False

    try:
        model = YOLO(model_ref)
        predictions = model.predict(
            source=str(video_path),
            stream=True,
            vid_stride=stride,
            conf=confidence,
            verbose=False,
        )
        for sample_index, result in enumerate(predictions):
            if sample_index >= max_frames:
                break

            frame_detections = _extract_detections(result, class_filter)
            detection_count += len(frame_detections)
            frames.append(
                {
                    "sampleIndex": sample_index,
                    "frameIndex": sample_index * stride,
                    "detections": frame_detections,
                }
            )

            if not preview_saved:
                plotted = result.plot()
                Image.fromarray(plotted[:, :, ::-1]).save(preview_path, quality=90)
                preview_saved = True
    except Exception as exc:
        return _fallback("yolo", model_ref, f"YOLO detection failed: {exc}")

    elapsed = max(0.001, time.perf_counter() - started)
    payload = {
        "mode": "yolo",
        "model": model_ref,
        "confidenceThreshold": confidence,
        "sampleStride": stride,
        "classFilter": sorted(class_filter),
        "frames": frames,
        "note": "COCO YOLO can detect person and sports ball. Referee identity requires a fine-tuned class later.",
    }
    detections_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")

    return DetectorRun(
        mode="yolo",
        status="completed",
        model=model_ref,
        sampled_frames=len(frames),
        detection_count=detection_count,
        fps=round(len(frames) / elapsed, 2),
        detections_path=str(detections_path),
        preview_path=str(preview_path) if preview_saved else None,
        note="YOLO detection completed; dispute events still use deterministic mock scoring.",
    )


def _extract_detections(result: Any, class_filter: set[str]) -> list[dict[str, Any]]:
    names = getattr(result, "names", {}) or {}
    boxes = getattr(result, "boxes", None)
    if boxes is None:
        return []

    detections: list[dict[str, Any]] = []
    for box in boxes:
        class_id = int(_scalar(box.cls))
        label = _label_for(names, class_id)
        if class_filter and label.lower() not in class_filter:
            continue
        detections.append(
            {
                "label": label,
                "classId": class_id,
                "confidence": round(float(_scalar(box.conf)), 4),
                "box": [round(float(value), 2) for value in box.xyxy[0].tolist()],
            }
        )
    return detections


def _label_for(names: Any, class_id: int) -> str:
    if isinstance(names, dict):
        return str(names.get(class_id, class_id))
    if isinstance(names, list) and 0 <= class_id < len(names):
        return str(names[class_id])
    return str(class_id)


def _scalar(value: Any) -> float:
    if hasattr(value, "item"):
        return float(value.item())
    if hasattr(value, "__len__"):
        return float(value[0])
    return float(value)


def _class_filter() -> set[str]:
    raw = _env("AI_YOLO_CLASSES", "person,sports ball")
    return {item.strip().lower() for item in raw.split(",") if item.strip()}


def _model_available(model_ref: str, allow_download: bool) -> bool:
    if Path(model_ref).exists():
        return True
    is_plain_weight_name = "/" not in model_ref and "\\" not in model_ref and model_ref.endswith(".pt")
    return allow_download and is_plain_weight_name


def _fallback(mode: str, model: str, note: str) -> DetectorRun:
    return DetectorRun(
        mode=mode,
        status="fallback",
        model=model or "mock-yolo-small",
        sampled_frames=0,
        detection_count=0,
        fps=0.0,
        detections_path=None,
        preview_path=None,
        note=note,
    )


def _env(name: str, default: str) -> str:
    value = os.getenv(name)
    return value.strip() if value and value.strip() else default


def _int_env(name: str, default: int) -> int:
    try:
        return int(_env(name, str(default)))
    except ValueError:
        return default


def _float_env(name: str, default: float) -> float:
    try:
        return float(_env(name, str(default)))
    except ValueError:
        return default
