package com.footballref.assist.controller;

import com.footballref.assist.domain.AnalysisTask;
import com.footballref.assist.domain.DisputeEvent;
import com.footballref.assist.domain.ReviewRecord;
import com.footballref.assist.domain.UserAccount;
import com.footballref.assist.domain.enums.EventType;
import com.footballref.assist.domain.enums.ReviewResult;
import com.footballref.assist.domain.enums.RiskLevel;
import com.footballref.assist.repository.AnalysisTaskRepository;
import com.footballref.assist.repository.DisputeEventRepository;
import com.footballref.assist.repository.ReviewRecordRepository;
import com.footballref.assist.service.CurrentUserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/events")
public class EventController {
    private final DisputeEventRepository events;
    private final ReviewRecordRepository reviews;
    private final AnalysisTaskRepository tasks;
    private final CurrentUserService currentUserService;

    public EventController(
            DisputeEventRepository events,
            ReviewRecordRepository reviews,
            AnalysisTaskRepository tasks,
            CurrentUserService currentUserService
    ) {
        this.events = events;
        this.reviews = reviews;
        this.tasks = tasks;
        this.currentUserService = currentUserService;
    }

    @GetMapping("/{eventId}")
    public Map<String, Object> detail(@PathVariable Long eventId) {
        DisputeEvent event = events.findById(eventId).orElseThrow();
        Map<String, Object> map = toEventMap(event);
        map.put("reviews", reviews.findByEventOrderByRevisionDesc(event).stream().map(this::toReviewMap).toList());
        return map;
    }

    @PostMapping("/{eventId}/review")
    public Map<String, Object> review(@PathVariable Long eventId, @Valid @RequestBody ReviewRequest request) {
        DisputeEvent event = events.findById(eventId).orElseThrow();
        UserAccount reviewer = currentUserService.requireUser();
        ReviewRecord record = new ReviewRecord();
        record.setEvent(event);
        record.setReviewer(reviewer);
        record.setResult(request.result());
        record.setReasonCode(request.reasonCode());
        record.setNote(request.note());
        record.setRevision((int) reviews.countByEvent(event) + 1);
        return toReviewMap(reviews.save(record));
    }

    @PatchMapping("/{eventId}/tracks")
    public Map<String, Object> updateTracks(@PathVariable Long eventId, @Valid @RequestBody UpdateTracksRequest request) {
        DisputeEvent event = events.findById(eventId).orElseThrow();
        event.setInvolvedTrackIds(String.join(",", request.involvedTrackIds().stream().map(String::valueOf).toList()));
        return toEventMap(events.save(event));
    }

    @PostMapping("/manual")
    public Map<String, Object> manual(@Valid @RequestBody ManualEventRequest request) {
        AnalysisTask task = tasks.findById(request.taskId()).orElseThrow();
        DisputeEvent event = new DisputeEvent();
        event.setTask(task);
        event.setExternalId("MANUAL-" + System.currentTimeMillis());
        event.setEventType(request.eventType());
        event.setRiskLevel(RiskLevel.UNCERTAIN);
        event.setTimestampMs(request.timestampMs());
        event.setWindowStartMs(Math.max(0, request.timestampMs() - 3000));
        event.setWindowEndMs(request.timestampMs() + 3000);
        event.setSummary(request.summary());
        event.setConfidence(0.0);
        event.setKeyframePath(task.getVideo().getOriginalPath());
        event.setClipPath(task.getVideo().getOriginalPath());
        event.setInvolvedTrackIds(String.join(",", request.involvedTrackIds().stream().map(String::valueOf).toList()));
        event.setEvidenceJson("[]");
        event.setTrackBoxesJson("[]");
        return toEventMap(events.save(event));
    }

    public static Map<String, Object> toEventMap(DisputeEvent event) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", event.getId());
        map.put("taskId", event.getTask().getId());
        map.put("externalId", event.getExternalId());
        map.put("eventType", event.getEventType());
        map.put("riskLevel", event.getRiskLevel());
        map.put("timestampMs", event.getTimestampMs());
        map.put("windowStartMs", event.getWindowStartMs());
        map.put("windowEndMs", event.getWindowEndMs());
        map.put("summary", event.getSummary());
        map.put("confidence", event.getConfidence());
        map.put("keyframePath", event.getKeyframePath());
        map.put("clipPath", event.getClipPath());
        map.put("involvedTrackIds", event.getInvolvedTrackIds());
        map.put("evidenceJson", event.getEvidenceJson());
        map.put("trackBoxesJson", event.getTrackBoxesJson());
        map.put("createdAt", event.getCreatedAt());
        return map;
    }

    private Map<String, Object> toReviewMap(ReviewRecord record) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", record.getId());
        map.put("eventId", record.getEvent().getId());
        map.put("reviewer", record.getReviewer().getDisplayName());
        map.put("result", record.getResult());
        map.put("reasonCode", record.getReasonCode());
        map.put("note", record.getNote());
        map.put("revision", record.getRevision());
        map.put("createdAt", record.getCreatedAt());
        return map;
    }

    public record ReviewRequest(@NotNull ReviewResult result, @NotBlank String reasonCode, String note) {
    }

    public record UpdateTracksRequest(@NotNull List<Long> involvedTrackIds) {
    }

    public record ManualEventRequest(
            @NotNull Long taskId,
            @NotNull EventType eventType,
            long timestampMs,
            @NotBlank String summary,
            @NotNull List<Long> involvedTrackIds
    ) {
    }
}

