package com.footballref.assist.domain;

import com.footballref.assist.domain.enums.TaskStatus;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(indexes = @Index(name = "idx_attempt_task_active", columnList = "taskId,active"))
public class TaskAttempt {
    @Id @Column(length = 36) private String id = UUID.randomUUID().toString();
    @Column(nullable = false) private Long taskId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 24) private TaskStatus status = TaskStatus.PENDING;
    @Column(nullable = false) private boolean active = true;
    @Column(nullable = false, length = 36) private String ownerInstance;
    @Column(nullable = false, updatable = false) private Instant createdAt = Instant.now();
    private Instant startedAt;
    private Instant finishedAt;
    @Column(length = 80) private String errorCode;
    @Column(nullable = false) private boolean executionFinished;
    @Column(length = 24) private String cancelOutcome;

    public boolean isExecutionFinished() { return executionFinished; }
    public void setExecutionFinished(boolean value) { executionFinished = value; }
    public String getCancelOutcome() { return cancelOutcome; }
    public void setCancelOutcome(String value) { cancelOutcome = value; }

    public String getId() { return id; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long value) { taskId = value; }
    public TaskStatus getStatus() { return status; }
    public void setStatus(TaskStatus value) { status = value; }
    public boolean isActive() { return active; }
    public void setActive(boolean value) { active = value; }
    public String getOwnerInstance() { return ownerInstance; }
    public void setOwnerInstance(String value) { ownerInstance = value; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant value) { startedAt = value; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant value) { finishedAt = value; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String value) { errorCode = value; }
}
