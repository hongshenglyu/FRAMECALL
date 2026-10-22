from __future__ import annotations

import json
import math
import os
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from .schemas import EvidenceMetric
from .tracker import TrackObservation, TrackState, TrackingRun


@dataclass(frozen=True)
class FoulCandidate:
    track_ids: tuple[int, int]
    timestamp_ms: int
    window_start_ms: int
    window_end_ms: int
    score: float
    confidence: float
    min_distance_px: float
    relative_velocity_delta: float
    closing_speed: float
    contact_window_score: float
    summary: str
    evidence_metrics: list[EvidenceMetric]


@dataclass(frozen=True)
class FoulRuleRun:
    mode: str
    status: str
    scorer: str
    candidate_count: int
    selected: FoulCandidate | None
    rules_path: str | None
    note: str


def score_foul_candidates(tracking_run: TrackingRun, output_dir: Path) -> FoulRuleRun:
    mode = _env("AI_FOUL_RULE_MODE", "track-rule-v1").lower()
    if mode == "mock":
        selected = _fallback_candidate("Mock foul candidate retained for demo stability.")
        return _write_rule_run(output_dir, mode, "completed", "mock-foul-rule", [selected], selected)

    if mode != "track-rule-v1":
        selected = _fallback_candidate(f"Unknown AI_FOUL_RULE_MODE={mode!r}; using fallback foul candidate.")
        return _write_rule_run(output_dir, mode, "fallback", "mock-foul-rule", [selected], selected)

    candidates = _score_track_pairs(tracking_run)
    if not candidates:
        selected = _fallback_candidate("Not enough track observations for distance/velocity scoring.")
        return _write_rule_run(output_dir, mode, "fallback", "track-rule-v1", [selected], selected)

    selected = max(candidates, key=lambda candidate: candidate.score)
    return _write_rule_run(output_dir, mode, "completed", "track-rule-v1", candidates, selected)


def _score_track_pairs(tracking_run: TrackingRun) -> list[FoulCandidate]:
    pairs = _candidate_pairs(tracking_run)
    candidates: list[FoulCandidate] = []
    for first_id, second_id in pairs:
        first = tracking_run.tracks.get(first_id)
        second = tracking_run.tracks.get(second_id)
        if first is None or second is None:
            continue

        observations = _paired_observations(first, second)
        if len(observations) < 3:
            continue

        distances = [
            (timestamp_ms, _distance(_center(left), _center(right)))
            for timestamp_ms, left, right in observations
        ]
        candidate_index, (timestamp_ms, min_distance) = min(enumerate(distances), key=lambda item: item[1][1])
        proximity_score = _clamp(1.0 - min_distance / _float_env("AI_FOUL_DISTANCE_THRESHOLD_PX", 140.0))
        closing_speed = _closing_speed(distances, candidate_index)
        closing_score = _clamp(closing_speed / _float_env("AI_FOUL_CLOSING_SPEED_PX_S", 40.0))
        velocity_delta = _relative_velocity_delta(observations, candidate_index)
        velocity_score = _clamp(velocity_delta / _float_env("AI_FOUL_VELOCITY_DELTA_PX_S", 30.0))
        contact_window_score = _clamp((proximity_score + closing_score) / 2)

        score = round(_clamp(0.52 * proximity_score + 0.33 * velocity_score + 0.15 * contact_window_score), 3)
        metrics = [
            EvidenceMetric(
                name="min_distance",
                value=round(min_distance, 2),
                unit="px",
                description="Minimum center distance between candidate players",
            ),
            EvidenceMetric(
                name="proximity",
                value=round(proximity_score, 3),
                unit="score",
                description="Distance compression score from player tracks",
            ),
            EvidenceMetric(
                name="relative_velocity_delta",
                value=round(velocity_delta, 2),
                unit="px/s",
                description="Change in relative velocity around the contact window",
            ),
            EvidenceMetric(
                name="closing_speed",
                value=round(closing_speed, 2),
                unit="px/s",
                description="Speed at which the two tracks close before the candidate frame",
            ),
            EvidenceMetric(
                name="contact_window",
                value=round(contact_window_score, 3),
                unit="score",
                description="Combined proximity and closing-window score",
            ),
        ]
        candidates.append(
            FoulCandidate(
                track_ids=(first_id, second_id),
                timestamp_ms=timestamp_ms,
                window_start_ms=max(0, timestamp_ms - 3000),
                window_end_ms=timestamp_ms + 3000,
                score=score,
                confidence=score,
                min_distance_px=round(min_distance, 2),
                relative_velocity_delta=round(velocity_delta, 2),
                closing_speed=round(closing_speed, 2),
                contact_window_score=round(contact_window_score, 3),
                summary="Track-rule foul candidate: close player pair with relative velocity change",
                evidence_metrics=metrics,
            )
        )
    return candidates


def _candidate_pairs(tracking_run: TrackingRun) -> list[tuple[int, int]]:
    explicit = _env("AI_FOUL_TRACK_PAIRS", "")
    if explicit:
        pairs: list[tuple[int, int]] = []
        for raw_pair in explicit.split(";"):
            parts = [part.strip() for part in raw_pair.split(",") if part.strip()]
            if len(parts) != 2:
                continue
            try:
                pairs.append((int(parts[0]), int(parts[1])))
            except ValueError:
                continue
        if pairs:
            return pairs

    track_ids = sorted(tracking_run.tracks)
    pairs = []
    for index, first_id in enumerate(track_ids):
        first_team = tracking_run.tracks[first_id].team
        for second_id in track_ids[index + 1:]:
            second_team = tracking_run.tracks[second_id].team
            if first_team and second_team and first_team == second_team:
                continue
            pairs.append((first_id, second_id))
    return pairs


def _paired_observations(first: TrackState, second: TrackState) -> list[tuple[int, TrackObservation, TrackObservation]]:
    second_by_time = {observation.timestamp_ms: observation for observation in second.observations}
    pairs = [
        (observation.timestamp_ms, observation, second_by_time[observation.timestamp_ms])
        for observation in first.observations
        if observation.timestamp_ms in second_by_time
    ]
    return sorted(pairs, key=lambda item: item[0])


def _closing_speed(distances: list[tuple[int, float]], candidate_index: int) -> float:
    if candidate_index <= 0:
        return 0.0
    previous_time, previous_distance = distances[candidate_index - 1]
    timestamp_ms, distance = distances[candidate_index]
    seconds = max(0.001, (timestamp_ms - previous_time) / 1000)
    return max(0.0, (previous_distance - distance) / seconds)


def _relative_velocity_delta(observations: list[tuple[int, TrackObservation, TrackObservation]], candidate_index: int) -> float:
    if candidate_index <= 0 or candidate_index >= len(observations) - 1:
        return 0.0

    before = _relative_velocity(observations[candidate_index - 1], observations[candidate_index])
    after = _relative_velocity(observations[candidate_index], observations[candidate_index + 1])
    return _distance(before, after)


def _relative_velocity(
    previous: tuple[int, TrackObservation, TrackObservation],
    current: tuple[int, TrackObservation, TrackObservation],
) -> tuple[float, float]:
    previous_time, previous_first, previous_second = previous
    current_time, current_first, current_second = current
    seconds = max(0.001, (current_time - previous_time) / 1000)
    first_velocity = _velocity(_center(previous_first), _center(current_first), seconds)
    second_velocity = _velocity(_center(previous_second), _center(current_second), seconds)
    return first_velocity[0] - second_velocity[0], first_velocity[1] - second_velocity[1]


def _velocity(previous: tuple[float, float], current: tuple[float, float], seconds: float) -> tuple[float, float]:
    return (current[0] - previous[0]) / seconds, (current[1] - previous[1]) / seconds


def _center(observation: TrackObservation) -> tuple[float, float]:
    x1, y1, x2, y2 = observation.box
    return (x1 + x2) / 2, (y1 + y2) / 2


def _distance(first: tuple[float, float], second: tuple[float, float]) -> float:
    return math.hypot(first[0] - second[0], first[1] - second[1])


def _fallback_candidate(note: str) -> FoulCandidate:
    metrics = [
        EvidenceMetric(name="min_distance", value=48.0, unit="px", description="Fallback player-pair distance"),
        EvidenceMetric(name="proximity", value=0.66, unit="score", description="Fallback proximity score"),
        EvidenceMetric(name="relative_velocity_delta", value=18.0, unit="px/s", description="Fallback velocity-change score"),
        EvidenceMetric(name="contact_window", value=0.62, unit="score", description=note),
    ]
    return FoulCandidate(
        track_ids=(7, 12),
        timestamp_ms=12400,
        window_start_ms=9400,
        window_end_ms=15400,
        score=0.67,
        confidence=0.67,
        min_distance_px=48.0,
        relative_velocity_delta=18.0,
        closing_speed=14.0,
        contact_window_score=0.62,
        summary="Fallback foul candidate for closed-loop demo",
        evidence_metrics=metrics,
    )


def _write_rule_run(
    output_dir: Path,
    mode: str,
    status: str,
    scorer: str,
    candidates: list[FoulCandidate],
    selected: FoulCandidate | None,
) -> FoulRuleRun:
    output_dir.mkdir(parents=True, exist_ok=True)
    rules_path = output_dir / "foul_candidates.json"
    note = "Foul candidates scored from track distance and relative velocity change."
    if status == "fallback":
        note = selected.summary if selected else "Foul rule fallback was used."

    payload = {
        "mode": mode,
        "status": status,
        "scorer": scorer,
        "candidateCount": len(candidates),
        "selected": _candidate_to_json(selected) if selected else None,
        "candidates": [_candidate_to_json(candidate) for candidate in candidates],
        "thresholds": {
            "distancePx": _float_env("AI_FOUL_DISTANCE_THRESHOLD_PX", 140.0),
            "velocityDeltaPxS": _float_env("AI_FOUL_VELOCITY_DELTA_PX_S", 30.0),
            "closingSpeedPxS": _float_env("AI_FOUL_CLOSING_SPEED_PX_S", 40.0),
        },
        "note": note,
    }
    rules_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")

    return FoulRuleRun(
        mode=mode,
        status=status,
        scorer=scorer,
        candidate_count=len(candidates),
        selected=selected,
        rules_path=str(rules_path),
        note=note,
    )


def _candidate_to_json(candidate: FoulCandidate | None) -> dict[str, Any] | None:
    if candidate is None:
        return None
    return {
        "trackIds": list(candidate.track_ids),
        "timestampMs": candidate.timestamp_ms,
        "windowStartMs": candidate.window_start_ms,
        "windowEndMs": candidate.window_end_ms,
        "score": candidate.score,
        "confidence": candidate.confidence,
        "minDistancePx": candidate.min_distance_px,
        "relativeVelocityDelta": candidate.relative_velocity_delta,
        "closingSpeed": candidate.closing_speed,
        "contactWindowScore": candidate.contact_window_score,
        "summary": candidate.summary,
        "evidenceMetrics": [metric.model_dump() for metric in candidate.evidence_metrics],
    }


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
