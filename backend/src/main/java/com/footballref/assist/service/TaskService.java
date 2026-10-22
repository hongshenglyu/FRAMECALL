package com.footballref.assist.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.footballref.assist.domain.AnalysisTask;
import com.footballref.assist.domain.DisputeEvent;
import com.footballref.assist.domain.enums.EventType;
import com.footballref.assist.domain.enums.RiskLevel;
import com.footballref.assist.domain.enums.TaskStatus;
import com.footballref.assist.repository.AnalysisTaskRepository;
import com.footballref.assist.repository.DisputeEventRepository;
import jakarta.transaction.Transactional;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class TaskService {
    private final AnalysisTaskRepository tasks;
    private final DisputeEventRepository events;
    private final StorageService storageService;
    private final AiClient aiClient;
    private final ObjectMapper objectMapper;

    public TaskService(
            AnalysisTaskRepository tasks,
            DisputeEventRepository events,
            StorageService storageService,
            AiClient aiClient,
            ObjectMapper objectMapper
    ) {
        this.tasks = tasks;
        this.events = events;
        this.storageService = storageService;
        this.aiClient = aiClient;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public AnalysisTask createTask(AnalysisTask task) throws IOException {
        AnalysisTask saved = tasks.save(task);
        Path taskDir = storageService.taskDir(saved.getId());
        saved.setOutputDir(taskDir.toString());
        return tasks.save(saved);
    }

    @Async
    @Transactional
    public void runTask(Long taskId) {
        AnalysisTask task = tasks.findById(taskId).orElseThrow();
        if (task.getStatus() == TaskStatus.CANCEL_REQUESTED) {
            markCancelled(task);
            return;
        }
        task.setStatus(TaskStatus.RUNNING);
        task.setStage("QUEUED");
        task.setProgressPercent(2);
        task.setErrorMessage(null);
        tasks.save(task);

        try {
            String callbackUrl = "http://localhost:8080/api/internal/tasks/" + taskId + "/progress";
            Map<String, Object> result = aiClient.analyze(task, callbackUrl);
            task = tasks.findById(taskId).orElseThrow();
            if (task.getStatus() == TaskStatus.CANCEL_REQUESTED) {
                markCancelled(task);
                return;
            }
            persistEvents(task, result);
            task.setResultJsonPath(string(result.get("resultJsonPath")));
            task.setStage("COMPLETED");
            task.setProgressPercent(100);
            task.setStatus(TaskStatus.SUCCEEDED);
            tasks.save(task);
        } catch (Exception ex) {
            task = tasks.findById(taskId).orElseThrow();
            if (task.getStatus() == TaskStatus.CANCEL_REQUESTED) {
                markCancelled(task);
            } else {
                task.setStatus(TaskStatus.FAILED);
                task.setStage("FAILED");
                task.setErrorMessage(ex.getMessage());
                tasks.save(task);
            }
        }
    }

    @Transactional
    public void requestCancel(Long taskId) {
        AnalysisTask task = tasks.findById(taskId).orElseThrow();
        if (task.getStatus() == TaskStatus.SUCCEEDED || task.getStatus() == TaskStatus.FAILED) {
            return;
        }
        task.setStatus(TaskStatus.CANCEL_REQUESTED);
        task.setStage("CANCEL_REQUESTED");
        tasks.save(task);
        aiClient.cancel(taskId);
    }

    @Transactional
    public void updateProgress(Long taskId, String stage, int percent) {
        AnalysisTask task = tasks.findById(taskId).orElseThrow();
        if (task.getStatus() == TaskStatus.RUNNING || task.getStatus() == TaskStatus.PENDING) {
            task.setStage(stage);
            task.setProgressPercent(percent);
            tasks.save(task);
        }
    }

    @Transactional
    public void retry(Long taskId) {
        AnalysisTask task = tasks.findById(taskId).orElseThrow();
        events.deleteByTask(task);
        task.setStatus(TaskStatus.PENDING);
        task.setStage("PENDING");
        task.setProgressPercent(0);
        task.setErrorMessage(null);
        tasks.save(task);
        runTask(task.getId());
    }

    private void markCancelled(AnalysisTask task) {
        task.setStatus(TaskStatus.CANCELLED);
        task.setStage("CANCELLED");
        task.setProgressPercent(0);
        tasks.save(task);
    }

    @SuppressWarnings("unchecked")
    private void persistEvents(AnalysisTask task, Map<String, Object> result) throws JsonProcessingException {
        events.deleteByTask(task);
        Object rawEvents = result.get("events");
        if (!(rawEvents instanceof List<?> eventList)) {
            return;
        }
        for (Object raw : eventList) {
            Map<String, Object> item = (Map<String, Object>) raw;
            DisputeEvent event = new DisputeEvent();
            event.setTask(task);
            event.setExternalId(string(item.get("externalId")));
            event.setEventType(EventType.valueOf(string(item.get("eventType"))));
            event.setRiskLevel(RiskLevel.valueOf(string(item.get("riskLevel"))));
            event.setTimestampMs(number(item.get("timestampMs")).longValue());
            event.setWindowStartMs(number(item.get("windowStartMs")).longValue());
            event.setWindowEndMs(number(item.get("windowEndMs")).longValue());
            event.setSummary(string(item.get("summary")));
            event.setConfidence(number(item.get("confidence")).doubleValue());
            event.setKeyframePath(storageService.normalizeStoragePath(string(item.get("keyframePath"))));
            event.setClipPath(storageService.normalizeStoragePath(string(item.get("clipPath"))));
            event.setInvolvedTrackIds(joinIds((List<?>) item.get("involvedTrackIds")));
            event.setEvidenceJson(objectMapper.writeValueAsString(item.get("evidenceMetrics")));
            event.setTrackBoxesJson(objectMapper.writeValueAsString(item.get("trackBoxes")));
            events.save(event);
        }
    }

    private static Number number(Object value) {
        if (value instanceof Number number) {
            return number;
        }
        return Double.parseDouble(Objects.toString(value, "0"));
    }

    private static String string(Object value) {
        return Objects.toString(value, "");
    }

    private static String joinIds(List<?> ids) {
        if (ids == null) {
            return "";
        }
        return ids.stream().map(Objects::toString).collect(Collectors.joining(","));
    }
}
