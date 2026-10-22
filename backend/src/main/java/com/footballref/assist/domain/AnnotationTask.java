package com.footballref.assist.domain;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
public class AnnotationTask {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 160)
    private String title;

    @Column(nullable = false, length = 300)
    private String source;

    @Column(nullable = false, length = 40)
    private String status = "REGISTERED";

    @Column(length = 600)
    private String labelFilePath;

    @Column(nullable = false)
    private int sampleCount = 0;

    @Column(nullable = false)
    private int eventCount = 0;

    @Column(length = 300)
    private String eventTypeSummary = "";

    @Column(length = 300)
    private String splitSummary = "";

    @ManyToOne(optional = false)
    private UserAccount createdBy;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getLabelFilePath() {
        return labelFilePath;
    }

    public void setLabelFilePath(String labelFilePath) {
        this.labelFilePath = labelFilePath;
    }

    public int getSampleCount() {
        return sampleCount;
    }

    public void setSampleCount(int sampleCount) {
        this.sampleCount = sampleCount;
    }

    public int getEventCount() {
        return eventCount;
    }

    public void setEventCount(int eventCount) {
        this.eventCount = eventCount;
    }

    public String getEventTypeSummary() {
        return eventTypeSummary;
    }

    public void setEventTypeSummary(String eventTypeSummary) {
        this.eventTypeSummary = eventTypeSummary;
    }

    public String getSplitSummary() {
        return splitSummary;
    }

    public void setSplitSummary(String splitSummary) {
        this.splitSummary = splitSummary;
    }

    public UserAccount getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(UserAccount createdBy) {
        this.createdBy = createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
