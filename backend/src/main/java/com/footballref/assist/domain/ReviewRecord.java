package com.footballref.assist.domain;

import com.footballref.assist.domain.enums.ReviewResult;
import jakarta.persistence.*;

import java.time.Instant;

@Entity
public class ReviewRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    private DisputeEvent event;

    @ManyToOne
    private ContactAnnotationRevision annotationRevision;
    public ContactAnnotationRevision getAnnotationRevision(){return annotationRevision;}
    public void setAnnotationRevision(ContactAnnotationRevision value){annotationRevision=value;}

    @ManyToOne(optional = false)
    private UserAccount reviewer;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private ReviewResult result;

    @Column(nullable = false, length = 120)
    private String reasonCode;

    @Column(length = 1000)
    private String note;

    @Column(nullable = false)
    private int revision;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public Long getId() {
        return id;
    }

    public DisputeEvent getEvent() {
        return event;
    }

    public void setEvent(DisputeEvent event) {
        this.event = event;
    }

    public UserAccount getReviewer() {
        return reviewer;
    }

    public void setReviewer(UserAccount reviewer) {
        this.reviewer = reviewer;
    }

    public ReviewResult getResult() {
        return result;
    }

    public void setResult(ReviewResult result) {
        this.result = result;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public void setReasonCode(String reasonCode) {
        this.reasonCode = reasonCode;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public int getRevision() {
        return revision;
    }

    public void setRevision(int revision) {
        this.revision = revision;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

