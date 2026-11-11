package com.footballref.assist.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.footballref.assist.domain.*;
import com.footballref.assist.domain.enums.*;
import com.footballref.assist.repository.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Import already computed real evidence into the existing task/event/review lifecycle. */
@Service
public class ContactImportService {
    private final VideoAssetRepository videos;
    private final AnalysisTaskRepository tasks;
    private final TaskAttemptRepository attempts;
    private final DisputeEventRepository events;
    private final StorageService storage;
    private final CurrentUserService currentUser;
    private final ObjectMapper mapper;

    public ContactImportService(VideoAssetRepository videos, AnalysisTaskRepository tasks, TaskAttemptRepository attempts,
                                DisputeEventRepository events, StorageService storage, CurrentUserService currentUser, ObjectMapper mapper) {
        this.videos=videos; this.tasks=tasks; this.attempts=attempts; this.events=events;
        this.storage=storage; this.currentUser=currentUser; this.mapper=mapper;
    }

    private static void require(boolean condition) {
        if (!condition) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid contact clip bundle or missing media");
    }

    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> importBundle(JsonNode bundle, List<MultipartFile> files) throws Exception {
        require(bundle != null && bundle.path("schema").asText().equals("framecall.contact-clips.v1")
                && bundle.path("method").asText().equals("combined"));
        JsonNode source=bundle.path("source"), bindings=bundle.path("events");
        require(bindings.isArray() && !bindings.isEmpty() && bindings.size()==bundle.path("summary").path("originalCandidates").asInt());
        Map<String,MultipartFile> uploads=new HashMap<>();
        for (MultipartFile file:files) require(file.getOriginalFilename()!=null && uploads.put(file.getOriginalFilename(),file)==null);
        Set<String> expected=new HashSet<>();
        for (JsonNode media:bundle.path("media")) {
            String name=media.path("file").asText();
            require(name.matches("[A-Za-z0-9_-]+\\.(mp4|jpg)") && expected.add(name) && uploads.containsKey(name));
            require(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(uploads.get(name).getBytes()))
                    .equals(media.path("sha256").asText()));
        }
        require(!expected.isEmpty() && expected.equals(uploads.keySet()));
        Map<String,JsonNode> clips=new HashMap<>();
        for (JsonNode clip:bundle.path("clips")) {
            require(clips.put(clip.path("clipId").asText(),clip)==null && expected.contains(clip.path("file").asText())
                    && clip.path("file").asText().endsWith(".mp4") && clip.path("encodedDurationMs").asDouble()>0);
        }
        require(!clips.isEmpty());
        Set<String> ids=new HashSet<>();
        for (JsonNode binding:bindings) {
            JsonNode e=binding.path("candidate");
            double start=e.path("startTimeMs").asDouble(), peak=e.path("peakTimeMs").asDouble(), end=e.path("endTimeMs").asDouble();
            require(ids.add(e.path("eventId").asText()) && e.path("eventId").asText().matches("CONTACT-[a-f0-9]{16}")
                    && e.path("matchId").equals(source.path("matchId")) && e.path("partId").equals(source.path("partId"))
                    && e.path("segmentId").equals(source.path("segmentId"))
                    && source.path("sampleStartTimeMs").asDouble()<=start && start<=peak && peak<=end
                    && end<=source.path("sampleEndTimeMs").asDouble()
                    && e.path("contactStatus").asText().equals("NOT_DETERMINED") && e.path("foulDecision").asText().equals("NOT_ASSESSED"));
            String[] timeBase=e.path("sourceTimeBase").asText().split("/");
            require(timeBase.length==2 && timeBase[0].matches("[0-9]+") && timeBase[1].matches("[1-9][0-9]*")
                    && Math.abs(e.path("peakSourcePts").asDouble()*1000
                    *Double.parseDouble(timeBase[0])/Double.parseDouble(timeBase[1])-peak)<0.1);
            if (!binding.path("clipId").isNull()) {
                JsonNode clip=clips.get(binding.path("clipId").asText());
                require(clip!=null && expected.contains(binding.path("keyframe").asText())
                        && binding.path("keyframe").asText().equals(e.path("eventId").asText()+".jpg"));
                double offset=binding.path("clipPeakTimeMs").asDouble();
                require(offset>=0 && offset<clip.path("encodedDurationMs").asDouble()
                        && Math.abs(offset-(peak-clip.path("firstSourceTimeMs").asDouble()))<0.1);
                boolean linked=false;
                for (JsonNode id:clip.path("eventIds")) if (id.asText().equals(e.path("eventId").asText())) linked=true;
                require(linked);
            } else require(binding.path("keyframe").isNull() && binding.path("clipPeakTimeMs").isNull());
        }
        VideoAsset video=new VideoAsset();
        video.setOwner(currentUser.requireUser()); video.setTitle("P4真实接触候选复核 / "+source.path("matchId").asText());
        video.setCompetition(source.path("event").asText()); video.setTeams(source.path("relativePath").asText().split("/")[0]);
        video.setOriginalPath(""); videos.save(video);
        AnalysisTask task=new AnalysisTask(); task.setVideo(video); task.setStatus(TaskStatus.SUCCEEDED);
        task.setStage("REAL_CONTACT_IMPORTED"); task.setProgressPercent(100); tasks.save(task);
        TaskAttempt attempt=new TaskAttempt(); attempt.setTaskId(task.getId()); attempt.setStatus(TaskStatus.SUCCEEDED);
        attempt.setOwnerInstance(UUID.randomUUID().toString()); attempt.setActive(false); attempt.setExecutionFinished(true);
        attempt.setStartedAt(Instant.now()); attempt.setFinishedAt(Instant.now()); attempts.save(attempt);
        task.setCurrentAttemptId(attempt.getId());
        Path directory=storage.attemptDir(task.getId(),attempt.getId());
        for (String name:expected) uploads.get(name).transferTo(directory.resolve(name));
        Path record=directory.resolve("bundle.json"); mapper.writeValue(record.toFile(),bundle);
        task.setOutputDir(directory.toString()); task.setResultJsonPath(record.toString()); tasks.save(task);
        video.setOriginalPath(directory.resolve(bundle.path("clips").get(0).path("file").asText()).toString()); videos.save(video);
        for (JsonNode binding:bindings) {
            JsonNode e=binding.path("candidate"), peak=e.path("peakEvidence");
            DisputeEvent event=new DisputeEvent(); event.setTask(task); event.setAttemptId(attempt.getId());
            event.setExternalId(e.path("eventId").asText()); event.setEventType(EventType.CONTACT); event.setRiskLevel(RiskLevel.UNCERTAIN);
            event.setTimestampMs(Math.round(e.path("peakTimeMs").asDouble()));
            event.setWindowStartMs(Math.round(e.path("startTimeMs").asDouble())); event.setWindowEndMs(Math.round(e.path("endTimeMs").asDouble()));
            event.setConfidence(e.path("candidateScore").asDouble());
            event.setSummary(binding.path("clipId").isNull()?"真实P3候选；本轮未提取片段，证据与原始记录保留":"真实P3接触候选；需查看原片并人工复核，不代表犯规");
            List<String> trackIds=new ArrayList<>();
            for (JsonNode p:e.path("trackParticipants")) { String key=p.path("trackKey").asText(); trackIds.add(key.substring(key.lastIndexOf(":T")+2)); }
            event.setInvolvedTrackIds(String.join(",",trackIds)); event.setTrackBoxesJson("[]");
            List<Map<String,Object>> metrics=new ArrayList<>();
            metrics.add(metric("min_distance",e.path("minNormalizedDistance"),"框高","事件证据最小归一化距离",null));
            metrics.add(metric("closing_speed",peak.path("closingSpeedHeightPerSecond"),"框高/秒","峰值帧接近速度（正值为接近）",peak.path("closingUnavailableReason")));
            metrics.add(metric("relative_velocity_delta",peak.path("relativeVelocityChangeHeightPerSecond"),"框高/秒","峰值帧相对速度向量变化，非加速度",peak.path("velocityChangeUnavailableReason")));
            event.setEvidenceJson(mapper.writeValueAsString(metrics));
            Map<String,Object> metadata=new LinkedHashMap<>(); metadata.put("source",source); metadata.put("candidate",e);
            metadata.put("clipPeakTimeMs",binding.get("clipPeakTimeMs"));
            JsonNode clip=clips.get(binding.path("clipId").asText());
            if (clip==null) metadata.put("clip",null);
            else { var compact=clip.deepCopy(); ((com.fasterxml.jackson.databind.node.ObjectNode)compact).remove("sourceFrames"); metadata.put("clip",compact); }
            event.setContactMetadataJson(mapper.writeValueAsString(metadata));
            event.setClipPath(clip==null?"":directory.resolve(clip.path("file").asText()).toString());
            event.setKeyframePath(clip==null?"":directory.resolve(binding.path("keyframe").asText()).toString());
            events.save(event);
        }
        return Map.of("task",com.footballref.assist.controller.TaskController.toTaskMap(task),"summary",bundle.path("summary"));
    }

    private static Map<String,Object> metric(String name, JsonNode value, String unit, String description, JsonNode reason) {
        Map<String,Object> metric=new LinkedHashMap<>(); metric.put("name",name); metric.put("value",value.isNumber()?value.numberValue():null);
        metric.put("unit",unit); metric.put("description",description); metric.put("unavailableReason",reason==null?null:reason.asText(null)); return metric;
    }
}
