from __future__ import annotations

from enum import Enum
from pathlib import Path
from typing import Any

from pydantic import BaseModel, Field


class EventType(str, Enum):
    FOUL = "FOUL"
    HANDBALL = "HANDBALL"
    OFFSIDE = "OFFSIDE"


class RiskLevel(str, Enum):
    HIGH = "HIGH"
    MEDIUM = "MEDIUM"
    LOW = "LOW"
    UNCERTAIN = "UNCERTAIN"


class Sensitivity(str, Enum):
    HIGH_RECALL = "HIGH_RECALL"
    BALANCED = "BALANCED"
    LOW_FALSE_POSITIVE = "LOW_FALSE_POSITIVE"


class AnalysisRequest(BaseModel):
    task_id: int = Field(alias="taskId")
    video_path: str = Field(alias="videoPath")
    output_dir: str = Field(alias="outputDir")
    sensitivity: Sensitivity = Sensitivity.BALANCED
    enabled_events: list[EventType] = Field(
        default_factory=lambda: [EventType.FOUL, EventType.HANDBALL, EventType.OFFSIDE],
        alias="enabledEvents",
    )
    callback_url: str | None = Field(default=None, alias="callbackUrl")
    internal_token: str | None = Field(default=None, alias="internalToken")


class EvidenceMetric(BaseModel):
    name: str
    value: float
    unit: str | None = None
    description: str


class TrackBox(BaseModel):
    track_id: int = Field(alias="trackId")
    label: str
    team: str | None = None
    box: list[float]
    confidence: float


class DisputeEventResult(BaseModel):
    external_id: str = Field(alias="externalId")
    event_type: EventType = Field(alias="eventType")
    risk_level: RiskLevel = Field(alias="riskLevel")
    timestamp_ms: int = Field(alias="timestampMs")
    window_start_ms: int = Field(alias="windowStartMs")
    window_end_ms: int = Field(alias="windowEndMs")
    involved_track_ids: list[int] = Field(alias="involvedTrackIds")
    summary: str
    evidence_metrics: list[EvidenceMetric] = Field(alias="evidenceMetrics")
    track_boxes: list[TrackBox] = Field(alias="trackBoxes")
    keyframe_path: str = Field(alias="keyframePath")
    clip_path: str = Field(alias="clipPath")
    confidence: float


class AnalysisResult(BaseModel):
    task_id: int = Field(alias="taskId")
    status: str
    stage: str
    progress_percent: int = Field(alias="progressPercent")
    result_json_path: str = Field(alias="resultJsonPath")
    events: list[DisputeEventResult]
    metrics: dict[str, Any]
    error_message: str | None = Field(default=None, alias="errorMessage")


class ProgressUpdate(BaseModel):
    task_id: int = Field(alias="taskId")
    stage: str
    progress_percent: int = Field(alias="progressPercent")
    message: str

