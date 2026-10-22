package com.footballref.assist.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.footballref.assist.domain.AnnotationTask;
import com.footballref.assist.domain.enums.EventType;
import com.footballref.assist.repository.AnnotationTaskRepository;
import com.footballref.assist.service.CurrentUserService;
import com.footballref.assist.service.StorageService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/annotations")
@PreAuthorize("hasRole('ADMIN')")
public class AnnotationController {
    private static final Set<String> SUPPORTED_SPLITS = Set.of("train", "val", "test", "demo");

    private final AnnotationTaskRepository annotations;
    private final CurrentUserService currentUserService;
    private final StorageService storageService;
    private final ObjectMapper objectMapper;

    public AnnotationController(
            AnnotationTaskRepository annotations,
            CurrentUserService currentUserService,
            StorageService storageService,
            ObjectMapper objectMapper
    ) {
        this.annotations = annotations;
        this.currentUserService = currentUserService;
        this.storageService = storageService;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return annotations.findAllByOrderByCreatedAtDesc().stream().map(this::toMap).toList();
    }

    @PostMapping
    public Map<String, Object> create(@Valid @RequestBody CreateAnnotationRequest request) {
        AnnotationTask task = new AnnotationTask();
        task.setTitle(request.title());
        task.setSource(request.source());
        task.setCreatedBy(currentUserService.requireUser());
        return toMap(annotations.save(task));
    }

    @PostMapping("/{annotationId}/labels")
    public Map<String, Object> uploadLabels(@PathVariable Long annotationId, @RequestParam MultipartFile file) throws Exception {
        AnnotationTask task = requireAnnotation(annotationId);
        byte[] content = file.getBytes();
        LabelStats stats = validateLabels(content);
        String originalName = StringUtils.cleanPath(file.getOriginalFilename() == null ? "labels.json" : file.getOriginalFilename());
        Path destination = storageService.annotationDir()
                .resolve(annotationId + "_" + Instant.now().toEpochMilli() + "_" + originalName.replaceAll("[^a-zA-Z0-9._-]", "_"));
        Files.write(destination, content);
        task.setLabelFilePath(destination.toString());
        task.setSampleCount(stats.sampleCount());
        task.setEventCount(stats.eventCount());
        task.setEventTypeSummary(stats.eventTypeSummary());
        task.setSplitSummary(stats.splitSummary());
        task.setStatus("LABEL_IMPORTED");
        return toMap(annotations.save(task));
    }

    @GetMapping("/{annotationId}/labels")
    public ResponseEntity<Resource> downloadLabels(@PathVariable Long annotationId) throws IOException {
        AnnotationTask task = requireAnnotation(annotationId);
        Path labels = requireLabelFile(task);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=annotation-labels-" + annotationId + ".json")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new FileSystemResource(labels));
    }

    @GetMapping("/{annotationId}/labels.csv")
    public ResponseEntity<byte[]> downloadLabelsCsv(@PathVariable Long annotationId) throws IOException {
        AnnotationTask task = requireAnnotation(annotationId);
        Path labels = requireLabelFile(task);
        byte[] csv = buildEventCsv(labels);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=annotation-labels-" + annotationId + ".csv")
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(csv);
    }

    @DeleteMapping("/{annotationId}")
    public Map<String, Object> delete(@PathVariable Long annotationId) throws IOException {
        AnnotationTask task = requireAnnotation(annotationId);
        String labelFilePath = task.getLabelFilePath();
        if (StringUtils.hasText(labelFilePath)) {
            Path labels = storageService.resolveStoragePath(labelFilePath);
            if (labels.startsWith(storageService.root())) {
                Files.deleteIfExists(labels);
            }
        }
        annotations.delete(task);
        return Map.of("deleted", true, "id", annotationId);
    }

    private Map<String, Object> toMap(AnnotationTask task) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", task.getId());
        map.put("title", task.getTitle());
        map.put("source", task.getSource());
        map.put("status", task.getStatus());
        map.put("labelFilePath", task.getLabelFilePath());
        map.put("sampleCount", task.getSampleCount());
        map.put("eventCount", task.getEventCount());
        map.put("eventTypeSummary", task.getEventTypeSummary());
        map.put("splitSummary", task.getSplitSummary());
        map.put("createdBy", task.getCreatedBy().getDisplayName());
        map.put("createdAt", task.getCreatedAt());
        return map;
    }

    private AnnotationTask requireAnnotation(Long annotationId) {
        return annotations.findById(annotationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "标注任务不存在"));
    }

    private Path requireLabelFile(AnnotationTask task) {
        if (!StringUtils.hasText(task.getLabelFilePath())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "标注文件尚未导入");
        }
        Path labels = storageService.resolveStoragePath(task.getLabelFilePath());
        if (!labels.startsWith(storageService.root())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "标注文件不在存储目录内");
        }
        if (!Files.exists(labels)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "标注文件不存在");
        }
        return labels;
    }

    private LabelStats validateLabels(byte[] content) {
        JsonNode root;
        try {
            root = objectMapper.readTree(content);
        } catch (IOException ex) {
            throw badRequest("标注文件必须是合法 JSON");
        }

        if (!root.isObject()) {
            throw badRequest("标注文件顶层必须是 JSON 对象");
        }
        if (!"annotation.v1".equals(root.path("schemaVersion").asText())) {
            throw badRequest("schemaVersion 必须为 annotation.v1");
        }
        JsonNode samples = root.path("samples");
        if (!samples.isArray()) {
            throw badRequest("samples 必须是数组");
        }

        EnumMap<EventType, Integer> typeCounts = new EnumMap<>(EventType.class);
        Map<String, Integer> splitCounts = new LinkedHashMap<>();
        int eventCount = 0;
        int sampleIndex = 0;
        for (JsonNode sample : samples) {
            if (!sample.isObject()) {
                throw badRequest("samples[" + sampleIndex + "] 必须是对象");
            }
            if (sample.path("sampleId").asText("").isBlank()) {
                throw badRequest("samples[" + sampleIndex + "].sampleId 不能为空");
            }
            String split = sample.path("split").asText("").trim().toLowerCase(Locale.ROOT);
            if (split.isBlank()) {
                throw badRequest("samples[" + sampleIndex + "].split 不能为空");
            }
            if (!SUPPORTED_SPLITS.contains(split)) {
                throw badRequest("不支持的 split: " + split);
            }
            splitCounts.merge(split, 1, Integer::sum);
            JsonNode sampleEvents = sample.path("events");
            if (!sampleEvents.isArray()) {
                throw badRequest("samples[" + sampleIndex + "].events 必须是数组");
            }
            int eventIndex = 0;
            for (JsonNode event : sampleEvents) {
                if (!event.isObject()) {
                    throw badRequest("samples[" + sampleIndex + "].events[" + eventIndex + "] 必须是对象");
                }
                String eventTypeValue = event.path("eventType").asText("");
                EventType eventType;
                try {
                    eventType = EventType.valueOf(eventTypeValue);
                } catch (IllegalArgumentException ex) {
                    throw badRequest("不支持的 eventType: " + eventTypeValue);
                }
                typeCounts.merge(eventType, 1, Integer::sum);
                eventCount++;
                eventIndex++;
            }
            sampleIndex++;
        }

        String summary = typeCounts.entrySet().stream()
                .map(entry -> entry.getKey().name() + "=" + entry.getValue())
                .collect(Collectors.joining(", "));
        String splitSummary = splitCounts.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining(", "));
        return new LabelStats(samples.size(), eventCount, summary, splitSummary);
    }

    private byte[] buildEventCsv(Path labels) throws IOException {
        JsonNode root = objectMapper.readTree(Files.readAllBytes(labels));
        StringBuilder csv = new StringBuilder();
        appendCsvRow(csv, List.of(
                "datasetId",
                "sampleId",
                "split",
                "eventId",
                "eventType",
                "label",
                "timestampMs",
                "keyframeFrameIndex",
                "windowStartMs",
                "windowEndMs",
                "involvedTrackIds",
                "expertDecision",
                "reasonCode",
                "confidence",
                "createdBy"
        ));

        String datasetId = root.path("datasetId").asText("");
        JsonNode samples = root.path("samples");
        if (samples.isArray()) {
            for (JsonNode sample : samples) {
                String sampleId = sample.path("sampleId").asText("");
                String split = sample.path("split").asText("");
                JsonNode events = sample.path("events");
                if (!events.isArray()) {
                    continue;
                }
                for (JsonNode event : events) {
                    JsonNode decision = event.path("decision");
                    appendCsvRow(csv, List.of(
                            datasetId,
                            sampleId,
                            split,
                            event.path("eventId").asText(""),
                            event.path("eventType").asText(""),
                            event.path("label").asText(""),
                            event.path("timestampMs").asText(""),
                            event.path("keyframe").path("frameIndex").asText(""),
                            event.path("windowStartMs").asText(""),
                            event.path("windowEndMs").asText(""),
                            joinValues(event.path("involvedTrackIds")),
                            decision.path("expertDecision").asText(""),
                            decision.path("reasonCode").asText(""),
                            decision.path("confidence").asText(""),
                            event.path("createdBy").asText("")
                    ));
                }
            }
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private void appendCsvRow(StringBuilder csv, List<String> values) {
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                csv.append(',');
            }
            appendCsvValue(csv, values.get(i));
        }
        csv.append("\r\n");
    }

    private void appendCsvValue(StringBuilder csv, String value) {
        String safeValue = value == null ? "" : value;
        boolean quoted = safeValue.contains(",") || safeValue.contains("\"")
                || safeValue.contains("\n") || safeValue.contains("\r");
        if (!quoted) {
            csv.append(safeValue);
            return;
        }
        csv.append('"');
        csv.append(safeValue.replace("\"", "\"\""));
        csv.append('"');
    }

    private String joinValues(JsonNode values) {
        if (!values.isArray()) {
            return values.asText("");
        }
        StringBuilder joined = new StringBuilder();
        for (JsonNode value : values) {
            if (!joined.isEmpty()) {
                joined.append(';');
            }
            joined.append(value.asText(""));
        }
        return joined.toString();
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    public record CreateAnnotationRequest(@NotBlank String title, @NotBlank String source) {
    }

    private record LabelStats(int sampleCount, int eventCount, String eventTypeSummary, String splitSummary) {
    }
}
