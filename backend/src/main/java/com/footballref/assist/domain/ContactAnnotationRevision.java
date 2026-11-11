package com.footballref.assist.domain;

import jakarta.persistence.*;
import java.time.Instant;

/** Append-only original annotation. Referee conclusions live separately in ReviewRecord. */
@Entity
public class ContactAnnotationRevision {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @ManyToOne(optional=false) private DisputeEvent event;
    @ManyToOne(optional=false) private UserAccount annotator;
    @Column(nullable=false,length=36) private String annotationKey;
    @Column(nullable=false) private int revision;
    @Column(nullable=false,length=24) private String recordType;
    @Column(nullable=false,length=24) private String status;
    @Lob @Column(nullable=false) private String annotationJson;
    @Column(nullable=false,updatable=false) private Instant createdAt=Instant.now();
    public Long getId(){return id;}
    public DisputeEvent getEvent(){return event;} public void setEvent(DisputeEvent v){event=v;}
    public UserAccount getAnnotator(){return annotator;} public void setAnnotator(UserAccount v){annotator=v;}
    public String getAnnotationKey(){return annotationKey;} public void setAnnotationKey(String v){annotationKey=v;}
    public int getRevision(){return revision;} public void setRevision(int v){revision=v;}
    public String getRecordType(){return recordType;} public void setRecordType(String v){recordType=v;}
    public String getStatus(){return status;} public void setStatus(String v){status=v;}
    public String getAnnotationJson(){return annotationJson;} public void setAnnotationJson(String v){annotationJson=v;}
    public Instant getCreatedAt(){return createdAt;}
}
