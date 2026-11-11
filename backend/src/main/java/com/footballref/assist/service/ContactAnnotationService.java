package com.footballref.assist.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.NullNode;
import com.footballref.assist.domain.*;
import com.footballref.assist.repository.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@Service
public class ContactAnnotationService {
    private final ContactAnnotationRevisionRepository annotations;
    private final ReviewRecordRepository reviews;
    private final ResourceAccessService access;
    private final TaskHistoryService history;
    private final CurrentUserService current;
    private final MediaAccessService media;
    private final ObjectMapper mapper;

    public ContactAnnotationService(ContactAnnotationRevisionRepository annotations, ReviewRecordRepository reviews,
            ResourceAccessService access, TaskHistoryService history, CurrentUserService current, MediaAccessService media, ObjectMapper mapper) {
        this.annotations=annotations; this.reviews=reviews; this.access=access; this.history=history;
        this.current=current; this.media=media; this.mapper=mapper;
    }
    private static void require(boolean value,String reason) {
        if(!value) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,reason);
    }
    private JsonNode metadata(DisputeEvent event) {
        try { JsonNode meta=mapper.readTree(event.getContactMetadataJson());
            require(meta.has("pilotSample"),"Only prepared pilot samples use the contact annotation workflow"); return meta;
        } catch (com.fasterxml.jackson.core.JsonProcessingException | NullPointerException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Pilot source metadata unavailable");
        }
    }
    private JsonNode frames(DisputeEvent event) {
        JsonNode meta=metadata(event);
        try {
            JsonNode bundle=mapper.readTree(media.controlledPath(event.getTask().getResultJsonPath()).toFile());
            for(JsonNode clip:bundle.path("clips")) if(clip.path("clipId").equals(meta.path("clip").path("clipId"))) {
                require(clip.path("sourceFrames").isArray() && !clip.path("sourceFrames").isEmpty(),"Original PTS frame map is missing");
                return clip.path("sourceFrames");
            }
        } catch(java.io.IOException e) { throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Original PTS frame map is missing"); }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Original PTS frame map is missing");
    }
    public Map<String,Object> detail(Long eventId) {
        DisputeEvent event=access.requireEvent(eventId);
        return Map.of("source",metadata(event).path("source"),"frames",frames(event),
                "revisions",annotations.findByEventOrderByIdDesc(event).stream().map(this::toMap).toList(),
                "currentAnnotator",Map.of("id",current.requireUser().getId(),"displayName",current.requireUser().getDisplayName()));
    }
    private JsonNode point(Double time,JsonNode frames) {
        if(time==null) return NullNode.getInstance();
        require(Double.isFinite(time),"Timestamp must be finite");
        for(JsonNode frame:frames) if(Math.abs(frame.path("timeMs").asDouble()-time)<0.1) return frame;
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Timestamp must reference an observed source PTS frame inside this clip");
    }
    public Map<String,Object> save(Long eventId, Request request) {
        return history.withEvent(eventId,(task,event)->{
            JsonNode meta=metadata(event), frameMap=frames(event);
            require(request.recordType()!=null && Set.of("HUMAN","FUNCTION_TEST").contains(request.recordType()),"Invalid annotation record type");
            require(request.status()!=null && Set.of("DRAFT","SUBMITTED").contains(request.status()),"Invalid annotation state");
            require(request.sceneType()==null || Set.of("CONTEST","NORMAL_APPROACH","NO_CONTEST","UNCLEAR").contains(request.sceneType()),"Invalid scene type");
            require(request.contactVisibility()==null || Set.of("VISIBLE_CONTACT","NO_VISIBLE_CONTACT","INSUFFICIENT_VIEW").contains(request.contactVisibility()),"Invalid contact visibility");
            require(request.note()==null || request.note().length()<=1000,"Note is too long");
            JsonNode start=point(request.contestStartTimeMs(),frameMap), first=point(request.firstContactTimeMs(),frameMap), end=point(request.contestEndTimeMs(),frameMap);
            if(!start.isNull() && !end.isNull()) require(start.path("timeMs").asDouble()<end.path("timeMs").asDouble(),"Contest start must precede end");
            if(!first.isNull()) {
                require("VISIBLE_CONTACT".equals(request.contactVisibility()),"A visible contact timestamp needs visible-contact evidence");
                require((start.isNull() || start.path("timeMs").asDouble()<=first.path("timeMs").asDouble())
                        && (end.isNull() || first.path("timeMs").asDouble()<=end.path("timeMs").asDouble()),"First visible contact must respect known contest boundaries");
            }
            if("SUBMITTED".equals(request.status())) {
                require(request.humanObserved() && request.sceneType()!=null && request.contactVisibility()!=null,"Submission needs explicit human inspection, scene type and visibility");
                if(Set.of("CONTEST","NORMAL_APPROACH").contains(request.sceneType())) require(!start.isNull() && !end.isNull(),"Observed contest/approach needs start and end");
                if("VISIBLE_CONTACT".equals(request.contactVisibility())) require(!first.isNull(),"Visible contact needs its first observed PTS");
                if("NO_CONTEST".equals(request.sceneType())) require(start.isNull() && first.isNull() && end.isNull() && "NO_VISIBLE_CONTACT".equals(request.contactVisibility()),"No contest must not invent contest times");
                if("UNCLEAR".equals(request.sceneType())) require("INSUFFICIENT_VIEW".equals(request.contactVisibility()),"Unclear footage requires insufficient-view visibility");
            }
            String key=request.annotationKey()==null?UUID.randomUUID().toString():request.annotationKey();
            require(key.matches("[a-f0-9-]{36}"),"Invalid annotation key");
            int previous=annotations.findByEventOrderByIdDesc(event).stream().filter(a->a.getAnnotationKey().equals(key)).mapToInt(ContactAnnotationRevision::getRevision).max().orElse(0);
            if(previous!=request.expectedRevision()) throw new ResponseStatusException(HttpStatus.CONFLICT,"Annotation changed; reload before creating another revision");
            Map<String,Object> payload=new LinkedHashMap<>();
            payload.put("matchId",meta.path("source").path("matchId")); payload.put("partId",meta.path("source").path("partId"));
            payload.put("segmentId",meta.path("source").path("segmentId")); payload.put("sceneType",request.sceneType());
            payload.put("contestStartTimeMs",start.isNull()?null:start.path("timeMs").asDouble());
            payload.put("firstContactTimeMs",first.isNull()?null:first.path("timeMs").asDouble());
            payload.put("contestEndTimeMs",end.isNull()?null:end.path("timeMs").asDouble());
            payload.put("sourcePoints",Map.of("contestStart",start,"firstContact",first,"contestEnd",end));
            payload.put("contactVisibility",request.contactVisibility()); payload.put("humanObserved",request.humanObserved());
            payload.put("note",request.note()); payload.put("formalGroundTruth",false);
            ContactAnnotationRevision row=new ContactAnnotationRevision(); row.setEvent(event); row.setAnnotator(current.requireUser());
            row.setAnnotationKey(key); row.setRevision(previous+1); row.setStatus(request.status()); row.setRecordType(request.recordType());
            try {row.setAnnotationJson(mapper.writeValueAsString(payload));} catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException(e);}
            return toMap(annotations.save(row));
        });
    }
    public void associateReview(ReviewRecord review,DisputeEvent event,Long revisionId) {
        if(revisionId==null) { require(!isPilot(event),"Save an original human annotation and select its submitted revision before referee review"); return; }
        ContactAnnotationRevision row=annotations.findById(revisionId).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Annotation revision not found"));
        require(row.getEvent().getId().equals(event.getId()) && row.getStatus().equals("SUBMITTED") && row.getRecordType().equals("HUMAN"),"Review must reference a submitted human annotation of this sample");
        review.setAnnotationRevision(row);
    }
    private boolean isPilot(DisputeEvent event) {
        try {return event.getContactMetadataJson()!=null && mapper.readTree(event.getContactMetadataJson()).has("pilotSample");}
        catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException(e);}
    }
    private Map<String,Object> toMap(ContactAnnotationRevision row) {
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("id",row.getId()); result.put("annotationKey",row.getAnnotationKey()); result.put("revision",row.getRevision());
        result.put("status",row.getStatus()); result.put("recordType",row.getRecordType()); result.put("createdAt",row.getCreatedAt());
        result.put("annotator",Map.of("id",row.getAnnotator().getId(),"displayName",row.getAnnotator().getDisplayName()));
        try{result.put("annotation",mapper.readTree(row.getAnnotationJson()));}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException(e);}
        result.put("reviewStatus",reviews.findByEventOrderByRevisionDesc(row.getEvent()).stream().anyMatch(r->r.getAnnotationRevision()!=null && r.getAnnotationRevision().getId().equals(row.getId()))?"REVIEWED":"PENDING");
        return result;
    }
    public Map<String,Object> export(Long taskId) {
        var view=history.view(taskId,"current",null);
        List<Map<String,Object>> samples=new ArrayList<>(); int humanSamples=0,humanEvents=0;
        for(DisputeEvent event:view.events()) if(isPilot(event)) {
            List<ContactAnnotationRevision> rows=annotations.findByEventOrderByIdDesc(event);
            Map<String,ContactAnnotationRevision> latest=new LinkedHashMap<>(); rows.forEach(row->latest.putIfAbsent(row.getAnnotationKey(),row));
            long submitted=latest.values().stream().filter(r->r.getRecordType().equals("HUMAN") && r.getStatus().equals("SUBMITTED")).count();
            if(submitted>0) humanSamples++; humanEvents+=Math.toIntExact(submitted);
            JsonNode meta=metadata(event);
            JsonNode frameMap=frames(event);
            samples.add(Map.of("sampleId",event.getExternalId(),"source",meta.path("source"),"sampling",meta.path("pilotSample"),
                    "clip",meta.path("clip"),"sourceFrameBounds",Map.of("first",frameMap.get(0),"last",frameMap.get(frameMap.size()-1)),
                    "annotations",rows.stream().map(this::toMap).toList(),
                    "reviews",reviews.findByEventOrderByRevisionDesc(event).stream().map(com.footballref.assist.controller.EventController::toReviewMap).toList()));
        }
        require(!samples.isEmpty(),"Task has no pilot samples");
        return Map.of("schema","framecall.contact-annotations.v1","samples",samples,"summary",Map.of("samples",samples.size(),
                "humanAnnotatedSamples",humanSamples,"humanSubmittedRecords",humanEvents,"unlabeledSamples",samples.size()-humanSamples,"formalGroundTruth",0));
    }
    public record Request(String annotationKey,int expectedRevision,String recordType,String status,String sceneType,
                          Double contestStartTimeMs,Double firstContactTimeMs,Double contestEndTimeMs,
                          String contactVisibility,boolean humanObserved,String note) {}
}
