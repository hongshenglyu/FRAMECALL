package com.footballref.assist.service;

import com.footballref.assist.domain.*;
import com.footballref.assist.domain.enums.TaskStatus;
import com.footballref.assist.repository.*;
import jakarta.persistence.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;

@Service
public class TaskHistoryService {
    private final AnalysisTaskRepository tasks;
    private final TaskAttemptRepository attempts;
    private final DisputeEventRepository events;
    private final ResourceAccessService access;
    private final CurrentUserService currentUser;
    private final TransactionTemplate transaction;
    @PersistenceContext private EntityManager entityManager;

    public TaskHistoryService(AnalysisTaskRepository tasks, TaskAttemptRepository attempts,
                              DisputeEventRepository events, ResourceAccessService access,
                              CurrentUserService currentUser, PlatformTransactionManager manager) {
        this.tasks=tasks; this.attempts=attempts; this.events=events; this.access=access; this.currentUser=currentUser;
        transaction=new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    private AnalysisTask locked(Long id, boolean includeArchived) {
        AnalysisTask task=tasks.lockById(id).orElseThrow(TaskHistoryService::notFound);
        entityManager.refresh(task,LockModeType.PESSIMISTIC_WRITE);
        if (!access.canAccessVideo(task.getVideo(),currentUser.requireUser())
                || !includeArchived && task.getArchivedAt()!=null) throw notFound();
        return task;
    }

    public <T> T withTask(Long id, Function<AnalysisTask,T> operation) {
        return transaction.execute(status->operation.apply(locked(id,false)));
    }

    public <T> T withEvent(Long eventId, BiFunction<AnalysisTask,DisputeEvent,T> operation) {
        DisputeEvent authorized=access.requireEvent(eventId);
        return withTask(authorized.getTask().getId(),task->{
            DisputeEvent event=events.findById(eventId).orElseThrow(TaskHistoryService::notFound);
            entityManager.refresh(event);
            if (event.getTask()==null || !task.getId().equals(event.getTask().getId())) throw notFound();
            return operation.apply(task,event);
        });
    }

    public AnalysisTask archive(Long id, boolean restore) {
        return transaction.execute(status->{
            AnalysisTask task=locked(id,true);
            if (attempts.existsByTaskIdAndActiveTrue(id) || task.getStatus()==TaskStatus.RUNNING
                    || task.getStatus()==TaskStatus.CANCEL_REQUESTED) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Task execution must be reconciled before archival");
            }
            if (restore ? task.getArchivedAt()!=null : task.getArchivedAt()==null) {
                task.setArchivedAt(restore?null:Instant.now()); tasks.save(task);
            }
            return task;
        });
    }

    public View view(Long id, String scope, String attemptId) {
        return withTask(id,task->{
            if (!List.of("current","all","legacy").contains(scope) || attemptId!=null && !scope.equals("current")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid history selection");
            }
            String selected=attemptId==null?task.getCurrentAttemptId():attemptId;
            if (attemptId!=null || scope.equals("current") && selected!=null) {
                TaskAttempt attempt=attempts.findById(selected).orElseThrow(TaskHistoryService::notFound);
                if (!id.equals(attempt.getTaskId())) throw notFound();
            }
            List<DisputeEvent> list=events.findByTaskOrderByTimestampMsAsc(task).stream()
                    .filter(event->scope.equals("all") || (scope.equals("legacy") ? event.getAttemptId()==null
                            : Objects.equals(selected,event.getAttemptId()))).toList();
            String label=attemptId!=null?"ATTEMPT:"+attemptId:scope.equals("all")?"ALL_HISTORY":scope.equals("legacy")||selected==null?"LEGACY":"CURRENT:"+selected;
            return new View(task,list,attempts.findByTaskIdOrderByCreatedAtAsc(id),label);
        });
    }

    public String association(DisputeEvent event) {
        if (event.getAttemptId()==null) return "LEGACY";
        return attempts.findById(event.getAttemptId()).filter(a->event.getTask()!=null
                && a.getTaskId().equals(event.getTask().getId())).isPresent()?"ATTEMPT":"UNKNOWN";
    }

    public String currentAttemptId(AnalysisTask task) {
        String id=task.getCurrentAttemptId();
        if (id!=null && attempts.findById(id).filter(a->task.getId().equals(a.getTaskId())).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Invalid current attempt association");
        }
        return id;
    }

    private static ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND,"Resource not found"); }
    public record View(AnalysisTask task,List<DisputeEvent> events,List<TaskAttempt> attempts,String label) { }
}
