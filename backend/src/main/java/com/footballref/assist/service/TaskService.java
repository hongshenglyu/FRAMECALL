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
import com.footballref.assist.domain.TaskAttempt;
import com.footballref.assist.repository.TaskAttemptRepository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private final TaskAttemptRepository attempts;
    private final AnalysisDispatcher dispatcher;
    private final TransactionTemplate transaction;
    private final String instance = UUID.randomUUID().toString();
    private static final Logger log = LoggerFactory.getLogger(TaskService.class);
    @PersistenceContext private EntityManager entityManager;

    public TaskService(
            AnalysisTaskRepository tasks,
            DisputeEventRepository events,
            StorageService storageService,
            AiClient aiClient,
            ObjectMapper objectMapper, TaskAttemptRepository attempts, AnalysisDispatcher dispatcher,
            PlatformTransactionManager manager
    ) {
        this.tasks = tasks;
        this.events = events;
        this.storageService = storageService;
        this.aiClient = aiClient;
        this.objectMapper = objectMapper;
        this.attempts = attempts;
        this.dispatcher = dispatcher;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Transactional
    public AnalysisTask createTask(AnalysisTask task) throws IOException {
        AnalysisTask saved = tasks.save(task);
        Path taskDir = storageService.taskDir(saved.getId());
        saved.setOutputDir(taskDir.toString());
        return tasks.save(saved);
    }

    public void runTask(Long taskId) { schedule(taskId, false); }

    public void retry(Long taskId) { schedule(taskId, true); }

    private AnalysisTask locked(Long taskId) {
        AnalysisTask task = tasks.lockById(taskId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Resource not found"));
        // Also refresh an entity already loaded by authorization under OpenEntityManagerInView.
        entityManager.refresh(task, LockModeType.PESSIMISTIC_WRITE);
        if (task.getArchivedAt() != null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Resource not found");
        return task;
    }

    private void schedule(Long taskId, boolean retry) {
        String attemptId = transaction.execute(status -> {
            AnalysisTask task = locked(taskId);
            boolean allowed = retry ? task.getStatus() == TaskStatus.FAILED || task.getStatus() == TaskStatus.CANCELLED
                    : task.getStatus() == TaskStatus.PENDING && task.getCurrentAttemptId() == null;
            if (!allowed || attempts.existsByTaskIdAndActiveTrue(taskId)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Task cannot start another attempt");
            }
            TaskAttempt attempt = new TaskAttempt();
            attempt.setTaskId(taskId); attempt.setOwnerInstance(instance);
            attempts.save(attempt);
            task.setCurrentAttemptId(attempt.getId());
            task.setStatus(TaskStatus.PENDING); task.setStage("QUEUED"); task.setProgressPercent(0);
            task.setErrorMessage(null); task.setResultJsonPath(null);
            task.setOutputDir(storageService.root().resolve("tasks/task-" + taskId + "/attempts/" + attempt.getId()).toString());
            tasks.save(task);
            trace(taskId, attempt, "QUEUED");
            return attempt.getId();
        });
        try { dispatcher.submit(() -> executeAttempt(taskId, attemptId)); }
        catch (java.util.concurrent.RejectedExecutionException rejected) {
            fail(taskId, attemptId, "DISPATCH_REJECTED");
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Analysis queue unavailable");
        }
    }

    private void executeAttempt(Long taskId, String attemptId) {
        try {
            AnalysisTask input = transaction.execute(status -> {
                AnalysisTask task = locked(taskId);
                TaskAttempt attempt = attempts.findById(attemptId).orElseThrow();
                if (!current(task, attempt) || attempt.getStatus() != TaskStatus.PENDING) return null;
                if (task.getStatus() == TaskStatus.CANCEL_REQUESTED) {
                    markCancelled(task); finish(attempt, TaskStatus.CANCELLED, null); return null;
                }
                if (task.getStatus() != TaskStatus.PENDING) return null;
                task.setStatus(TaskStatus.RUNNING); task.setStage("RUNNING"); task.setProgressPercent(2);
                attempt.setStatus(TaskStatus.RUNNING); attempt.setStartedAt(Instant.now());
                tasks.save(task); attempts.save(attempt); trace(taskId, attempt, "RUNNING");
                return task;
            });
            if (input == null) return;
            storageService.attemptDir(taskId, attemptId);
            // This blocking call deliberately runs outside any database transaction.
            Map<String, Object> result = aiClient.analyze(input, "configured-internal-callback");
            if (!"SUCCEEDED".equals(result.get("status")) || !(result.get("events") instanceof List<?>)) {
                throw new IllegalStateException("INVALID_RESULT");
            }
            transaction.executeWithoutResult(status -> {
                AnalysisTask task = locked(taskId);
                TaskAttempt attempt = attempts.findById(attemptId).orElseThrow();
                if (!current(task, attempt) || isolated(attempt)) return;
                if (task.getStatus() == TaskStatus.CANCEL_REQUESTED) {
                    attempt.setExecutionFinished(true);
                    settleCancellation(task, attempt); return;
                }
                if (task.getStatus() != TaskStatus.RUNNING || attempt.getStatus() != TaskStatus.RUNNING) return;
                try { persistEvents(task, result); }
                catch (JsonProcessingException failure) { throw new IllegalStateException("INVALID_RESULT"); }
                task.setResultJsonPath(string(result.get("resultJsonPath")));
                task.setStage("COMPLETED"); task.setProgressPercent(100); task.setStatus(TaskStatus.SUCCEEDED);
                tasks.save(task); finish(attempt, TaskStatus.SUCCEEDED, null);
            });
        } catch (Exception failure) {
            boolean uncertain = failure instanceof AiClient.UncertainExecutionException;
            fail(taskId, attemptId, uncertain ? "AI_OUTCOME_UNKNOWN" : "ANALYSIS_FAILED", uncertain);
        }
    }

    private boolean current(AnalysisTask task, TaskAttempt attempt) {
        return attempt.isActive() && Objects.equals(task.getCurrentAttemptId(), attempt.getId());
    }

    private void fail(Long taskId, String attemptId, String code) {
        fail(taskId, attemptId, code, false);
    }

    private void fail(Long taskId, String attemptId, String code, boolean uncertain) {
        transaction.executeWithoutResult(status -> {
            if (!tasks.existsById(taskId)) return;
            AnalysisTask task = locked(taskId);
            TaskAttempt attempt = attempts.findById(attemptId).orElseThrow();
            if (!current(task, attempt) || isolated(attempt)) return;
            if (uncertain) {
                TaskStatus state = task.getStatus() == TaskStatus.CANCEL_REQUESTED ? TaskStatus.CANCEL_REQUESTED : TaskStatus.FAILED;
                task.setStatus(state); task.setStage("RECOVERY_REQUIRED"); task.setErrorMessage(code);
                attempt.setStatus(state); attempt.setErrorCode(code); attempt.setExecutionFinished(true);
                // Keep active=true: losing HTTP transport does not prove the remote worker stopped.
                tasks.save(task); attempts.save(attempt); trace(taskId, attempt, code);
            } else if (task.getStatus() == TaskStatus.CANCEL_REQUESTED) {
                attempt.setExecutionFinished(true); settleCancellation(task, attempt);
            } else {
                if (task.getStatus() != TaskStatus.RUNNING && task.getStatus() != TaskStatus.PENDING) return;
                task.setStatus(TaskStatus.FAILED); task.setStage("FAILED"); task.setErrorMessage(code);
                tasks.save(task); finish(attempt, TaskStatus.FAILED, code);
            }
        });
    }

    private void finish(TaskAttempt attempt, TaskStatus status, String code) {
        attempt.setStatus(status); attempt.setActive(false); attempt.setExecutionFinished(true);
        attempt.setFinishedAt(Instant.now()); attempt.setErrorCode(code);
        attempts.save(attempt); trace(attempt.getTaskId(), attempt, status.name());
    }

    private void trace(Long taskId, TaskAttempt attempt, String transition) {
        log.info("analysis task={} attempt={} transition={} at={} thread={}", taskId, attempt.getId(),
                transition, Instant.now(), Thread.currentThread().getName());
    }

    @EventListener(ApplicationReadyEvent.class)
    public void quarantineInterruptedAttempts() {
        // Single application instance only. Never replay work whose AI execution may still exist.
        for (TaskAttempt previous : attempts.findByActiveTrue()) {
            transaction.executeWithoutResult(status -> {
                if (!tasks.existsById(previous.getTaskId())) return;
                AnalysisTask task = locked(previous.getTaskId());
                TaskAttempt attempt = attempts.findById(previous.getId()).orElseThrow();
                if (!current(task, attempt)) return;
                attempt.setStatus(TaskStatus.FAILED); attempt.setErrorCode("RECOVERY_REQUIRED");
                attempt.setFinishedAt(Instant.now()); attempts.save(attempt);
                task.setStatus(TaskStatus.FAILED); task.setStage("RECOVERY_REQUIRED"); task.setErrorMessage("RECOVERY_REQUIRED");
                tasks.save(task); trace(task.getId(), attempt, "RECOVERY_REQUIRED");
            });
        }
    }

    public HttpStatus requestCancel(Long taskId) {
        String attemptId = transaction.execute(status -> {
            AnalysisTask task = locked(taskId);
            if (task.getStatus() == TaskStatus.CANCELLED || task.getStatus() == TaskStatus.CANCEL_REQUESTED) return null;
            if (task.getStatus() != TaskStatus.PENDING && task.getStatus() != TaskStatus.RUNNING) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Task cannot be cancelled");
            }
            TaskAttempt attempt = task.getCurrentAttemptId() == null ? null
                    : attempts.findById(task.getCurrentAttemptId()).orElseThrow();
            if (task.getStatus() == TaskStatus.PENDING && (attempt == null
                    || current(task, attempt) && attempt.getStatus() == TaskStatus.PENDING)) {
                markCancelled(task);
                if (attempt != null) finish(attempt, TaskStatus.CANCELLED, null);
                return null;
            }
            if (attempt == null || !current(task, attempt) || isolated(attempt) || attempt.getStatus() != TaskStatus.RUNNING) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Task requires reconciliation");
            }
            // This transaction commit is the cancellation barrier shared with completion/progress.
            task.setStatus(TaskStatus.CANCEL_REQUESTED); task.setStage("CANCEL_REQUESTED");
            task.setProgressPercent(Math.min(task.getProgressPercent(), 99)); task.setErrorMessage(null);
            attempt.setStatus(TaskStatus.CANCEL_REQUESTED); attempt.setCancelOutcome("SENDING");
            tasks.save(task); attempts.save(attempt); trace(taskId, attempt, "CANCEL_REQUESTED");
            return attempt.getId();
        });
        if (attemptId != null) {
            String outcome;
            try { aiClient.cancel(taskId); outcome = "ACCEPTED"; }
            catch (AiClient.CancellationRejectedException rejected) { outcome = "REJECTED"; }
            catch (Exception uncertain) { outcome = "UNKNOWN"; }
            String resolved = outcome;
            // Acknowledgment is not worker termination. Both I/O calls must settle before retry.
            transaction.executeWithoutResult(status -> {
                AnalysisTask task = locked(taskId);
                TaskAttempt attempt = attempts.findById(attemptId).orElseThrow();
                if (!current(task, attempt) || task.getStatus() != TaskStatus.CANCEL_REQUESTED) return;
                attempt.setCancelOutcome(resolved);
                if (resolved.equals("UNKNOWN")) {
                    attempt.setErrorCode("CANCEL_OUTCOME_UNKNOWN");
                    task.setStage("RECOVERY_REQUIRED"); task.setErrorMessage("CANCEL_OUTCOME_UNKNOWN");
                } else if (resolved.equals("REJECTED") && !isolated(attempt)) {
                    attempt.setErrorCode("CANCEL_REMOTE_REJECTED"); task.setErrorMessage("CANCEL_REMOTE_REJECTED");
                }
                settleCancellation(task, attempt); trace(taskId, attempt, "CANCEL_" + resolved);
            });
        }
        return transaction.execute(status -> locked(taskId).getStatus() == TaskStatus.CANCELLED ? HttpStatus.OK : HttpStatus.ACCEPTED);
    }

    private boolean isolated(TaskAttempt attempt) {
        return List.of("RECOVERY_REQUIRED", "AI_OUTCOME_UNKNOWN", "CANCEL_OUTCOME_UNKNOWN").contains(
                attempt.getErrorCode() == null ? "" : attempt.getErrorCode());
    }

    private void settleCancellation(AnalysisTask task, TaskAttempt attempt) {
        if (attempt.isExecutionFinished() && !isolated(attempt)
                && ("ACCEPTED".equals(attempt.getCancelOutcome()) || "REJECTED".equals(attempt.getCancelOutcome()))) {
            markCancelled(task); finish(attempt, TaskStatus.CANCELLED, attempt.getErrorCode());
        } else {
            tasks.save(task); attempts.save(attempt); trace(task.getId(), attempt, "CANCEL_WAITING");
        }
    }

    public void updateProgress(Long taskId, String attemptId, String stage, int percent) {
        transaction.executeWithoutResult(status -> {
            AnalysisTask task = locked(taskId);
            if (!Objects.equals(task.getCurrentAttemptId(), attemptId)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Stale analysis attempt");
            }
            if (task.getStatus() != TaskStatus.RUNNING && !(attemptId == null && task.getStatus() == TaskStatus.PENDING)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Progress is closed for this task");
            }
            if (attemptId != null) {
                TaskAttempt attempt = attempts.findById(attemptId).orElseThrow();
                if (!current(task, attempt) || attempt.getStatus() != TaskStatus.RUNNING || isolated(attempt)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Progress is closed for this attempt");
                }
            }
            List<String> stages = List.of("EXTRACTING", "DETECTING", "TRACKING", "EVENT_ANALYSIS", "COMPLETED");
            int nextStage = stages.indexOf(stage), previousStage = stages.indexOf(task.getStage());
            if (nextStage < 0 || percent < 0 || percent > 100) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid analysis progress");
            }
            int bounded = Math.min(percent, 99);
            if (nextStage < previousStage || bounded < task.getProgressPercent()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Out of order analysis progress");
            }
            if (stage.equals(task.getStage()) && bounded == task.getProgressPercent()) return;
            task.setStage(stage); task.setProgressPercent(bounded); tasks.save(task);
            log.info("analysis progress task={} attempt={} stage={} percent={} at={} thread={}", taskId,
                    attemptId, stage, bounded, Instant.now(), Thread.currentThread().getName());
        });
    }

    private void markCancelled(AnalysisTask task) {
        task.setStatus(TaskStatus.CANCELLED);
        task.setStage("CANCELLED");
        task.setProgressPercent(Math.min(task.getProgressPercent(), 99));
        tasks.save(task);
    }

    @SuppressWarnings("unchecked")
    private void persistEvents(AnalysisTask task, Map<String, Object> result) throws JsonProcessingException {
        Object rawEvents = result.get("events");
        if (!(rawEvents instanceof List<?> eventList)) {
            return;
        }
        for (Object raw : eventList) {
            Map<String, Object> item = (Map<String, Object>) raw;
            DisputeEvent event = new DisputeEvent();
            event.setTask(task);
            event.setAttemptId(task.getCurrentAttemptId());
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
