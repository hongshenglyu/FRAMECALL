from __future__ import annotations

from fastapi import FastAPI, HTTPException

from .analyzer import run_analysis
from .detector import detector_settings
from .pose import pose_settings
from .schemas import AnalysisRequest, AnalysisResult
from .tracker import tracker_settings

app = FastAPI(title="Football Referee AI Service", version="0.1.0")

_cancelled_tasks: set[int] = set()


@app.get("/health")
def health() -> dict[str, str]:
    detector = detector_settings()
    tracker = tracker_settings()
    pose = pose_settings()
    return {
        "status": "ok",
        "service": "ai-service",
        "analyzerMode": detector["mode"],
        "yoloModel": detector["model"],
        "trackerMode": tracker["mode"],
        "poseMode": pose["mode"],
    }


@app.post("/internal/analyze", response_model=AnalysisResult)
def analyze(request: AnalysisRequest) -> AnalysisResult:
    try:
        _cancelled_tasks.discard(request.task_id)
        return run_analysis(request, cancel_checker=lambda task_id: task_id in _cancelled_tasks)
    except RuntimeError as exc:
        raise HTTPException(status_code=409, detail=str(exc)) from exc
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc


@app.post("/internal/tasks/{task_id}/cancel")
def cancel(task_id: int) -> dict[str, str | int]:
    _cancelled_tasks.add(task_id)
    return {"taskId": task_id, "status": "CANCEL_REQUESTED"}
