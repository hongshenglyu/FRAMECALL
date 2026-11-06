package com.footballref.assist.domain;

import com.footballref.assist.domain.enums.Sensitivity;
import com.footballref.assist.domain.enums.TaskStatus;
import jakarta.persistence.*;

import java.time.Instant;

@Entity
public class AnalysisTask {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 36)
    private String currentAttemptId;

    private Instant archivedAt;
    public Instant getArchivedAt() { return archivedAt; }
    public void setArchivedAt(Instant value) { archivedAt = value; }

    public String getCurrentAttemptId() { return currentAttemptId; }
    public void setCurrentAttemptId(String value) { currentAttemptId = value; }

    @ManyToOne(optional = false)
    private VideoAsset video;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private TaskStatus status = TaskStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Sensitivity sensitivity = Sensitivity.BALANCED;

    @Column(nullable = false, length = 80)
    private String stage = "PENDING";

    @Column(nullable = false)
    private int progressPercent = 0;

    @Column(length = 600)
    private String outputDir;

    @Column(length = 600)
    private String resultJsonPath;

    @Column(length = 1200)
    private String errorMessage;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    public void touch() {
        updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public VideoAsset getVideo() {
        return video;
    }

    public void setVideo(VideoAsset video) {
        this.video = video;
    }

    public TaskStatus getStatus() {
        return status;
    }

    public void setStatus(TaskStatus status) {
        this.status = status;
    }

    public Sensitivity getSensitivity() {
        return sensitivity;
    }

    public void setSensitivity(Sensitivity sensitivity) {
        this.sensitivity = sensitivity;
    }

    public String getStage() {
        return stage;
    }

    public void setStage(String stage) {
        this.stage = stage;
    }

    public int getProgressPercent() {
        return progressPercent;
    }

    public void setProgressPercent(int progressPercent) {
        this.progressPercent = progressPercent;
    }

    public String getOutputDir() {
        return outputDir;
    }

    public void setOutputDir(String outputDir) {
        this.outputDir = outputDir;
    }

    public String getResultJsonPath() {
        return resultJsonPath;
    }

    public void setResultJsonPath(String resultJsonPath) {
        this.resultJsonPath = resultJsonPath;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

