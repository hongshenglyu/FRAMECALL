from __future__ import annotations

from fastapi import FastAPI, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from .analyzer import run_analysis
from .detector import detector_settings
from .pose import pose_settings
from .schemas import AnalysisRequest, AnalysisResult
from .tracker import tracker_settings
from .security import service_authenticated, validate_analysis, callback_scope, reject

app = FastAPI(title="Football Referee AI Service", version="0.1.0", docs_url=None, redoc_url=None, openapi_url=None)


@app.middleware("http")
async def internal_identity(request: Request, call_next):
    if request.url.path == "/health" and request.method == "GET":
        return await call_next(request)
    try:
        if not service_authenticated(request.headers.get("X-AI-Service-Token")):
            return JSONResponse(status_code=401, content={"code": "INTERNAL_AUTH_REQUIRED"})
    except HTTPException as exc:
        return JSONResponse(status_code=exc.status_code, content=exc.detail)
    return await call_next(request)


@app.exception_handler(RequestValidationError)
async def invalid_request(request: Request, exception: RequestValidationError):
    # Pydantic's default error includes the rejected input (possibly a token or private path).
    return JSONResponse(status_code=422, content={"code": "INVALID_REQUEST"})

_cancelled_tasks: set[int] = set()


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok", "service": "ai-service"}


@app.post("/internal/analyze", response_model=AnalysisResult)
def analyze(request: AnalysisRequest, http_request: Request) -> AnalysisResult:
    token = http_request.headers.get("X-Callback-Token")
    output, target = validate_analysis(request, token)
    if detector_settings()["mode"] != "mock" or tracker_settings()["mode"] != "mock":
        reject("REAL_PIPELINE_UNAVAILABLE", 503)
    try:
        _cancelled_tasks.discard(request.task_id)
        with callback_scope(target, token):
            return run_analysis(request.model_copy(update={"output_dir": str(output)}), cancel_checker=lambda task_id: task_id in _cancelled_tasks)
    except HTTPException:
        raise
    except RuntimeError:
        raise HTTPException(status_code=409, detail={"code": "ANALYSIS_CANCELLED"}) from None
    except Exception:
        raise HTTPException(status_code=500, detail={"code": "ANALYSIS_FAILED"}) from None


@app.post("/internal/tasks/{task_id}/cancel")
def cancel(task_id: int) -> dict[str, str | int]:
    if task_id <= 0:
        reject("INVALID_TASK")
    _cancelled_tasks.add(task_id)
    return {"taskId": task_id, "status": "CANCEL_REQUESTED"}
