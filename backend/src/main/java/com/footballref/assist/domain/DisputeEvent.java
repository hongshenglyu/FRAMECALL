package com.footballref.assist.domain;

import com.footballref.assist.domain.enums.EventType;
import com.footballref.assist.domain.enums.RiskLevel;
import jakarta.persistence.*;

import java.time.Instant;

@Entity
public class DisputeEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    private AnalysisTask task;

    @Column(nullable = false, length = 80)
    private String externalId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private EventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private RiskLevel riskLevel;

    @Column(nullable = false)
    private long timestampMs;

    @Column(nullable = false)
    private long windowStartMs;

    @Column(nullable = false)
    private long windowEndMs;

    @Column(nullable = false, length = 500)
    private String summary;

    @Column(nullable = false)
    private double confidence;

    @Column(nullable = false, length = 600)
    private String keyframePath;

    @Column(nullable = false, length = 600)
    private String clipPath;

    @Column(nullable = false, length = 200)
    private String involvedTrackIds;

    @Lob
    private String evidenceJson;

    @Lob
    private String trackBoxesJson;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public Long getId() {
        return id;
    }

    public AnalysisTask getTask() {
        return task;
    }

    public void setTask(AnalysisTask task) {
        this.task = task;
    }

    public String getExternalId() {
        return externalId;
    }

    public void setExternalId(String externalId) {
        this.externalId = externalId;
    }

    public EventType getEventType() {
        return eventType;
    }

    public void setEventType(EventType eventType) {
        this.eventType = eventType;
    }

    public RiskLevel getRiskLevel() {
        return riskLevel;
    }

    public void setRiskLevel(RiskLevel riskLevel) {
        this.riskLevel = riskLevel;
    }

    public long getTimestampMs() {
        return timestampMs;
    }

    public void setTimestampMs(long timestampMs) {
        this.timestampMs = timestampMs;
    }

    public long getWindowStartMs() {
        return windowStartMs;
    }

    public void setWindowStartMs(long windowStartMs) {
        this.windowStartMs = windowStartMs;
    }

    public long getWindowEndMs() {
        return windowEndMs;
    }

    public void setWindowEndMs(long windowEndMs) {
        this.windowEndMs = windowEndMs;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public double getConfidence() {
        return confidence;
    }

    public void setConfidence(double confidence) {
        this.confidence = confidence;
    }

    public String getKeyframePath() {
        return keyframePath;
    }

    public void setKeyframePath(String keyframePath) {
        this.keyframePath = keyframePath;
    }

    public String getClipPath() {
        return clipPath;
    }

    public void setClipPath(String clipPath) {
        this.clipPath = clipPath;
    }

    public String getInvolvedTrackIds() {
        return involvedTrackIds;
    }

    public void setInvolvedTrackIds(String involvedTrackIds) {
        this.involvedTrackIds = involvedTrackIds;
    }

    public String getEvidenceJson() {
        return evidenceJson;
    }

    public void setEvidenceJson(String evidenceJson) {
        this.evidenceJson = evidenceJson;
    }

    public String getTrackBoxesJson() {
        return trackBoxesJson;
    }

    public void setTrackBoxesJson(String trackBoxesJson) {
        this.trackBoxesJson = trackBoxesJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

