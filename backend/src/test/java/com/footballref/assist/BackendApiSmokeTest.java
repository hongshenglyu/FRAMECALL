package com.footballref.assist;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.footballref.assist.domain.AnalysisTask;
import com.footballref.assist.domain.DisputeEvent;
import com.footballref.assist.domain.UserAccount;
import com.footballref.assist.domain.VideoAsset;
import com.footballref.assist.domain.enums.EventType;
import com.footballref.assist.domain.enums.RiskLevel;
import com.footballref.assist.domain.enums.Role;
import com.footballref.assist.domain.enums.TaskStatus;
import com.footballref.assist.repository.AnalysisTaskRepository;
import com.footballref.assist.repository.AnnotationTaskRepository;
import com.footballref.assist.repository.DisputeEventRepository;
import com.footballref.assist.repository.ReviewRecordRepository;
import com.footballref.assist.repository.UserAccountRepository;
import com.footballref.assist.repository.VideoAssetRepository;
import com.footballref.assist.service.PasswordService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BackendApiSmokeTest {
    private static final Path TEST_STORAGE_ROOT = createTempRoot();
    private String adminUsername;
    private String adminPassword;
    @Autowired PasswordService passwords;

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    UserAccountRepository users;

    @Autowired
    VideoAssetRepository videos;

    @Autowired
    AnnotationTaskRepository annotations;

    @Autowired
    AnalysisTaskRepository tasks;

    @Autowired
    DisputeEventRepository events;

    @Autowired
    ReviewRecordRepository reviews;

    @DynamicPropertySource
    static void configureTestProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:backend-api-smoke;MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("app.storage-root", () -> TEST_STORAGE_ROOT.toString());
    }

    @BeforeEach
    void cleanDomainData() {
        annotations.deleteAll();
        reviews.deleteAll();
        events.deleteAll();
        tasks.deleteAll();
        videos.deleteAll();
        users.deleteAll();
        adminUsername = "fixture_" + java.util.UUID.randomUUID();
        adminPassword = java.util.UUID.randomUUID().toString() + java.util.UUID.randomUUID();
        UserAccount admin = new UserAccount();
        admin.setUsername(adminUsername); admin.setDisplayName("Synthetic smoke administrator");
        admin.setRole(Role.ADMIN); admin.setEnabled(true); admin.setPasswordHash(passwords.hash(adminPassword));
        users.save(admin);
    }

    @AfterAll
    static void deleteStorageRoot() throws IOException {
        if (!Files.exists(TEST_STORAGE_ROOT)) {
            return;
        }
        try (var paths = Files.walk(TEST_STORAGE_ROOT)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        }
    }

    @Test
    void loginTaskDetailEventDetailAndReportExportWork() throws Exception {
        String token = loginAndReadToken();
        SeededTask seeded = seedSucceededTask();

        JsonNode task = getJson("/api/tasks/" + seeded.task().getId(), token);
        assertThat(task.path("id").asLong()).isEqualTo(seeded.task().getId());
        assertThat(task.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(task.path("events")).hasSize(1);
        assertThat(task.path("events").get(0).path("id").asLong()).isEqualTo(seeded.event().getId());
        assertThat(task.path("events").get(0).path("eventType").asText()).isEqualTo("FOUL");

        JsonNode event = getJson("/api/events/" + seeded.event().getId(), token);
        assertThat(event.path("id").asLong()).isEqualTo(seeded.event().getId());
        assertThat(event.path("reviews")).isEmpty();
        assertThat(event.path("evidenceJson").asText()).contains("min_distance");

        ResponseEntity<byte[]> report = rest.exchange(
                url("/api/tasks/" + seeded.task().getId() + "/report"),
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(token)),
                byte[].class
        );
        assertThat(report.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(report.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
        assertThat(report.getBody()).isNotNull();
        assertThat(new String(report.getBody(), 0, 4)).isEqualTo("%PDF");
    }

    @Test
    void annotationUploadValidatesSchemaAndStoresStats() throws Exception {
        String token = loginAndReadToken();
        JsonNode annotation = postJson(
                "/api/annotations",
                token,
                Map.of("title", "Small label batch", "source", "campus-football-small-v1")
        );
        long annotationId = annotation.path("id").asLong();

        JsonNode uploaded = uploadLabels(annotationId, token, validAnnotationLabels(), "labels.json", HttpStatus.OK);
        assertThat(uploaded.path("status").asText()).isEqualTo("LABEL_IMPORTED");
        assertThat(uploaded.path("sampleCount").asInt()).isEqualTo(2);
        assertThat(uploaded.path("eventCount").asInt()).isEqualTo(3);
        assertThat(uploaded.path("eventTypeSummary").asText()).contains("FOUL=1", "HANDBALL=1", "OFFSIDE=1");
        assertThat(uploaded.path("splitSummary").asText()).contains("demo=1", "test=1");

        ResponseEntity<String> labelsJson = rest.exchange(
                url("/api/annotations/" + annotationId + "/labels"),
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(token)),
                String.class
        );
        assertThat(labelsJson.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(labelsJson.getHeaders().getContentType()).isNotNull();
        assertThat(labelsJson.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue();
        assertThat(labelsJson.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).contains("annotation-labels-" + annotationId + ".json");
        assertThat(labelsJson.getBody()).contains("\"schemaVersion\": \"annotation.v1\"");

        ResponseEntity<String> labelsCsv = rest.exchange(
                url("/api/annotations/" + annotationId + "/labels.csv"),
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(token)),
                String.class
        );
        assertThat(labelsCsv.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(labelsCsv.getHeaders().getContentType()).isNotNull();
        assertThat(labelsCsv.getHeaders().getContentType().isCompatibleWith(MediaType.parseMediaType("text/csv"))).isTrue();
        assertThat(labelsCsv.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).contains("annotation-labels-" + annotationId + ".csv");
        assertThat(labelsCsv.getBody())
                .contains("datasetId,sampleId,split,eventId,eventType,label,timestampMs,keyframeFrameIndex")
                .contains("campus-football-small-v1,clip-0001,test,clip-0001-e01,FOUL")
                .contains("campus-football-small-v1,clip-0002,demo,clip-0002-e01,OFFSIDE");

        uploadLabels(annotationId, token, "{\"schemaVersion\":\"wrong\",\"samples\":[]}", "invalid.json", HttpStatus.BAD_REQUEST);

        ResponseEntity<String> deleted = rest.exchange(
                url("/api/annotations/" + annotationId),
                HttpMethod.DELETE,
                new HttpEntity<>(authHeaders(token)),
                String.class
        );
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(annotations.findById(annotationId)).isEmpty();
    }

    private String loginAndReadToken() throws Exception {
        ResponseEntity<String> response = rest.postForEntity(
                url("/api/auth/login"),
                Map.of("username", adminUsername, "password", adminPassword),
                String.class
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode payload = objectMapper.readTree(response.getBody());
        assertThat(payload.path("user").path("role").asText()).isEqualTo("ADMIN");
        return payload.path("token").asText();
    }

    private JsonNode getJson(String path, String token) throws Exception {
        ResponseEntity<String> response = rest.exchange(
                url(path),
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(token)),
                String.class
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody());
    }

    private JsonNode postJson(String path, String token, Map<String, Object> payload) throws Exception {
        HttpHeaders headers = authHeaders(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = rest.exchange(
                url(path),
                HttpMethod.POST,
                new HttpEntity<>(payload, headers),
                String.class
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody());
    }

    private JsonNode uploadLabels(long annotationId, String token, String content, String filename, HttpStatus expectedStatus) throws Exception {
        HttpHeaders headers = authHeaders(token);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return filename;
            }
        });

        ResponseEntity<String> response = rest.exchange(
                url("/api/annotations/" + annotationId + "/labels"),
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                String.class
        );
        assertThat(response.getStatusCode()).isEqualTo(expectedStatus);
        MediaType contentType = response.getHeaders().getContentType();
        if (response.getBody() == null || response.getBody().isBlank()
                || contentType == null
                || !contentType.isCompatibleWith(MediaType.APPLICATION_JSON)) {
            return objectMapper.createObjectNode();
        }
        return objectMapper.readTree(response.getBody());
    }

    private HttpHeaders authHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private SeededTask seedSucceededTask() throws Exception {
        UserAccount admin = users.findByUsername(adminUsername).orElseThrow();
        Path taskDir = Files.createDirectories(TEST_STORAGE_ROOT.resolve("tasks").resolve("test-task"));
        Path videoPath = TEST_STORAGE_ROOT.resolve("uploads").resolve("test-video.mp4");
        Files.createDirectories(videoPath.getParent());
        Files.writeString(videoPath, "test video placeholder");
        Path keyframePath = taskDir.resolve("keyframe.png");
        writeKeyframe(keyframePath);

        VideoAsset video = new VideoAsset();
        video.setTitle("Backend smoke video");
        video.setCompetition("Graduation Demo Cup");
        video.setTeams("Blue FC vs Red FC");
        video.setOriginalPath(videoPath.toString());
        video.setOwner(admin);
        video = videos.save(video);

        AnalysisTask task = new AnalysisTask();
        task.setVideo(video);
        task.setStatus(TaskStatus.SUCCEEDED);
        task.setStage("COMPLETED");
        task.setProgressPercent(100);
        task.setOutputDir(taskDir.toString());
        task.setResultJsonPath(taskDir.resolve("analysis_result.json").toString());
        task = tasks.save(task);

        DisputeEvent event = new DisputeEvent();
        event.setTask(task);
        event.setExternalId("T" + task.getId() + "-E01");
        event.setEventType(EventType.FOUL);
        event.setRiskLevel(RiskLevel.HIGH);
        event.setTimestampMs(12_400);
        event.setWindowStartMs(9_400);
        event.setWindowEndMs(15_400);
        event.setSummary("Track-rule foul candidate for backend API smoke.");
        event.setConfidence(0.83);
        event.setKeyframePath(keyframePath.toString());
        event.setClipPath(taskDir.resolve("clip_placeholder.txt").toString());
        event.setInvolvedTrackIds("7,12");
        event.setEvidenceJson("""
                [{"name":"min_distance","value":35.78,"unit":"px","description":"Minimum center distance"}]
                """);
        event.setTrackBoxesJson("""
                [{"trackId":7,"label":"player","team":"A","box":[159,121,239,301],"confidence":0.88}]
                """);
        event = events.save(event);
        return new SeededTask(task, event);
    }

    private void writeKeyframe(Path path) throws IOException {
        BufferedImage image = new BufferedImage(320, 180, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(246, 247, 249));
            graphics.fillRect(0, 0, 320, 180);
            graphics.setColor(new Color(47, 125, 50));
            graphics.drawRect(18, 24, 284, 132);
            graphics.setColor(new Color(220, 38, 38));
            graphics.drawRect(130, 48, 34, 74);
            graphics.setColor(new Color(37, 99, 235));
            graphics.drawRect(156, 50, 34, 74);
        } finally {
            graphics.dispose();
        }
        ImageIO.write(image, "png", path.toFile());
    }

    private String validAnnotationLabels() {
        return """
                {
                  "schemaVersion": "annotation.v1",
                  "datasetId": "campus-football-small-v1",
                  "datasetName": "Campus Football Small Set",
                  "createdAt": "2026-06-20T10:00:00+08:00",
                  "updatedAt": "2026-06-20T10:00:00+08:00",
                  "annotators": [
                    {
                      "annotatorId": "referee-001",
                      "displayName": "Referee A",
                      "role": "REFEREE"
                    }
                  ],
                  "samples": [
                    {
                      "sampleId": "clip-0001",
                      "source": {
                        "type": "local_file",
                        "uri": "storage/uploads/clip-0001.mp4",
                        "originalName": "clip-0001.mp4"
                      },
                      "split": "test",
                      "durationMs": 75000,
                      "fps": 25.0,
                      "events": [
                        {
                          "eventId": "clip-0001-e01",
                          "eventType": "FOUL",
                          "label": "CONFIRMED",
                          "timestampMs": 12400,
                          "keyframe": {
                            "frameIndex": 310,
                            "timestampMs": 12400,
                            "toleranceFrames": 3
                          },
                          "windowStartMs": 9400,
                          "windowEndMs": 15400,
                          "decision": {
                            "expertDecision": "FOUL",
                            "reviewResult": "CONFIRMED",
                            "confidence": 0.9
                          },
                          "createdBy": "referee-001",
                          "createdAt": "2026-06-20T10:05:00+08:00"
                        },
                        {
                          "eventId": "clip-0001-e02",
                          "eventType": "HANDBALL",
                          "label": "UNCERTAIN",
                          "timestampMs": 28750,
                          "keyframe": {
                            "frameIndex": 719,
                            "timestampMs": 28750,
                            "toleranceFrames": 3
                          },
                          "windowStartMs": 25750,
                          "windowEndMs": 31750,
                          "decision": {
                            "expertDecision": "HANDBALL_REVIEW",
                            "reviewResult": "UNCERTAIN",
                            "confidence": 0.6
                          },
                          "createdBy": "referee-001",
                          "createdAt": "2026-06-20T10:06:00+08:00"
                        }
                      ]
                    },
                    {
                      "sampleId": "clip-0002",
                      "source": {
                        "type": "local_file",
                        "uri": "storage/uploads/clip-0002.mp4",
                        "originalName": "clip-0002.mp4"
                      },
                      "split": "demo",
                      "durationMs": 60000,
                      "fps": 25.0,
                      "events": [
                        {
                          "eventId": "clip-0002-e01",
                          "eventType": "OFFSIDE",
                          "label": "CONFIRMED",
                          "timestampMs": 49200,
                          "keyframe": {
                            "frameIndex": 1230,
                            "timestampMs": 49200,
                            "toleranceFrames": 3
                          },
                          "windowStartMs": 46200,
                          "windowEndMs": 52200,
                          "decision": {
                            "expertDecision": "OFFSIDE_REVIEW",
                            "reviewResult": "CONFIRMED",
                            "confidence": 0.85
                          },
                          "createdBy": "referee-001",
                          "createdAt": "2026-06-20T10:07:00+08:00"
                        }
                      ]
                    }
                  ]
                }
                """;
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private static Path createTempRoot() {
        try {
            return Files.createTempDirectory("football-backend-test-");
        } catch (IOException ex) {
            throw new IllegalStateException("Could not create backend test storage directory", ex);
        }
    }

    private record SeededTask(AnalysisTask task, DisputeEvent event) {
    }
}
