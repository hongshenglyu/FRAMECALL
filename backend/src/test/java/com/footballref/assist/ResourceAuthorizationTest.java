package com.footballref.assist;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.footballref.assist.domain.*;
import com.footballref.assist.domain.enums.*;
import com.footballref.assist.repository.*;
import com.footballref.assist.service.AiClient;
import com.footballref.assist.service.JwtService;
import com.footballref.assist.service.ResourceAccessService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ResourceAuthorizationTest {
    private static final Path STORAGE = createStorage();
    private static final String DATABASE = "jdbc:h2:mem:auth_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserAccountRepository users;
    @Autowired VideoAssetRepository videos;
    @Autowired AnalysisTaskRepository tasks;
    @Autowired DisputeEventRepository events;
    @Autowired ReviewRecordRepository reviews;
    @Autowired JwtService jwt;
    @Autowired ResourceAccessService access;
    @MockBean AiClient aiClient;

    private UserAccount owner;
    private UserAccount other;
    private UserAccount admin;
    private UserAccount assigned;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> DATABASE);
        properties.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        properties.add("spring.datasource.username", () -> "wbs_fixture");
        properties.add("spring.datasource.password", () -> "");
        properties.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        properties.add("app.storage-root", STORAGE::toString);
    }

    @BeforeEach
    void prepareIsolatedAccounts() throws Exception {
        reviews.deleteAll();
        events.deleteAll();
        tasks.deleteAll();
        videos.deleteAll();
        users.deleteAll();
        owner = user("owner", Role.REFEREE);
        other = user("other", Role.REFEREE);
        admin = user("admin", Role.ADMIN);
        assigned = user("assigned", Role.REFEREE);
        when(aiClient.analyze(any(), anyString())).thenReturn(Map.of(
                "status", "SUCCEEDED",
                "events", List.of(), "resultJsonPath", STORAGE.resolve("synthetic-result.json").toString()));
        ImageIO.write(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", STORAGE.resolve("source.png").toFile());
    }

    @Test
    void authenticatedUploadAndManualReviewKeepServerOwnedIdentity() throws Exception {
        MockMultipartFile input = new MockMultipartFile("file", "synthetic.mp4", "video/mp4", new byte[]{1, 2, 3});
        MvcResult upload = call("CREATE_OWNER", owner, multipart("/api/videos").file(input)
                .param("title", "synthetic").param("competition", "fixture").param("teams", "fixture")
                .param("owner", other.getId().toString()).param("userId", other.getId().toString()).param("role", "ADMIN"), 200);
        long taskId = json.readTree(upload.getResponse().getContentAsByteArray()).path("task").path("id").asLong();
        awaitCompleted(taskId);
        assertEquals(owner.getId(), tasks.findById(taskId).orElseThrow().getVideo().getOwner().getId());
        MvcResult manual = call("CREATE_MANUAL_OWNER", owner, manual(taskId), 200);
        long eventId = json.readTree(manual.getResponse().getContentAsByteArray()).path("id").asLong();
        call("REVIEW_OWNER", owner, review(eventId), 200);
        ReviewRecord record = reviews.findByEventOrderByRevisionDesc(events.findById(eventId).orElseThrow()).get(0);
        assertEquals(owner.getId(), record.getReviewer().getId());
        assertEquals(taskId, record.getEvent().getTask().getId());
        call("READ_OWN_REVIEWS", owner, get("/api/events/" + eventId), 200);
    }

    @Test
    void anotherAuthenticatedUserCannotReadOrMutateKnownResourceIds() throws Exception {
        Fixture fixture = fixture(null);
        call("SETUP_OWNER_REVIEW", owner, review(fixture.eventId()), 200);
        long reviewCount = reviews.count();
        long eventCount = events.count();
        for (String operation : operations()) {
            call("DENY_B_" + operation, other, request(operation, fixture), 404);
        }
        assertEquals(reviewCount, reviews.count());
        assertEquals(eventCount, events.count());
        assertEquals(TaskStatus.PENDING, tasks.findById(fixture.taskId()).orElseThrow().getStatus());
        assertEquals("7,12", events.findById(fixture.eventId()).orElseThrow().getInvolvedTrackIds());
        assertFalse(Files.exists(Path.of(tasks.findById(fixture.taskId()).orElseThrow().getOutputDir()).resolve("referee-review-report.pdf")));
        verifyNoInteractions(aiClient);
    }

    @Test
    void ownerCanUseEveryExistingTaskAndEventOperation() throws Exception {
        assertAuthorizedOperations(owner, null, "ALLOW_A_");
    }

    @Test
    void administratorRetainsExistingAllResourceAccess() throws Exception {
        assertAuthorizedOperations(admin, null, "ALLOW_ADMIN_");
    }

    @Test
    void assignedRefereeRetainsExistingSharedResourceAccess() throws Exception {
        assertAuthorizedOperations(assigned, assigned, "ALLOW_ASSIGNED_");
    }

    @Test
    void taskListKeepsOwnerAssignmentAndAdministratorSemantics() throws Exception {
        Fixture owned = fixture(null);
        Fixture shared = fixture(assigned);
        for (UserAccount actor : List.of(owner, other, admin, assigned)) {
            MvcResult result = call("LIST_" + actor.getDisplayName(), actor, get("/api/tasks"), 200);
            var data = json.readTree(result.getResponse().getContentAsByteArray());
            int expected = actor == other ? 0 : actor == assigned ? 1 : 2;
            assertEquals(expected, data.size());
            if (actor == assigned) {
                assertEquals(shared.taskId(), data.get(0).path("id").asLong());
                assertNotEquals(owned.taskId(), data.get(0).path("id").asLong());
            }
        }
    }

    @Test
    void missingIdsUseTheSameSafeNotFoundResponseBeforeSideEffects() throws Exception {
        Fixture missing = new Fixture(Long.MAX_VALUE, Long.MAX_VALUE);
        for (UserAccount actor : List.of(owner, admin)) {
            for (String operation : operations()) {
                call("MISSING_" + actor.getDisplayName() + "_" + operation, actor, request(operation, missing), 404);
            }
        }
        assertEquals(0, tasks.count());
        assertEquals(0, events.count());
        assertEquals(0, reviews.count());
        verifyNoInteractions(aiClient);
    }

    @Test
    void clientRoleHintsAndStaleAdminClaimsCannotOverrideDatabaseRole() throws Exception {
        Fixture fixture = fixture(null);
        call("DENY_CLIENT_ROLE", other, get("/api/tasks/" + fixture.taskId()).param("role", "ADMIN")
                .header("X-Role", "ADMIN"), 404);
        String previouslyIssued = jwt.issue(admin);
        admin.setRole(Role.REFEREE);
        users.save(admin);
        MvcResult result = mvc.perform(get("/api/tasks/" + fixture.taskId())
                .header("Authorization", "Bearer " + previouslyIssued)).andReturn();
        record("DENY_STALE_ADMIN_CLAIM", "persisted_referee", "GET", "/api/tasks/{id}", 404, result.getResponse().getStatus());
        assertEquals(404, result.getResponse().getStatus());
    }

    @Test
    void malformedOwnerOrParentRelationshipsFailClosedEvenForAdministrator() {
        for (UserAccount actor : List.of(owner, other, admin)) {
            assertFalse(access.canAccessTask(null, actor));
            AnalysisTask task = new AnalysisTask();
            assertFalse(access.canAccessTask(task, actor));
            VideoAsset video = new VideoAsset();
            task.setVideo(video);
            assertFalse(access.canAccessTask(task, actor));
            video.setAssignedTo(actor);
            assertFalse(access.canAccessTask(task, actor));
            video.setOwner(new UserAccount());
            assertFalse(access.canAccessTask(task, actor));
        }
        assertFalse(access.canAccessTask(new AnalysisTask(), null));
    }

    private void assertAuthorizedOperations(UserAccount actor, UserAccount assignee, String prefix) throws Exception {
        for (String operation : operations()) {
            Fixture fixture = fixture(assignee);
            if (operation.equals("RETRY")) {
                AnalysisTask retryable = tasks.findById(fixture.taskId()).orElseThrow();
                retryable.setStatus(TaskStatus.FAILED); tasks.save(retryable);
            }
            MvcResult result = call(prefix + operation, actor, request(operation, fixture), 200);
            if (operation.equals("RETRY")) awaitCompleted(fixture.taskId());
            switch (operation) {
                case "CANCEL" -> assertEquals(TaskStatus.CANCELLED, tasks.findById(fixture.taskId()).orElseThrow().getStatus());
                case "RETRY" -> assertEquals(TaskStatus.SUCCEEDED, tasks.findById(fixture.taskId()).orElseThrow().getStatus());
                case "DELETE" -> {
                    assertTrue(tasks.existsById(fixture.taskId()));
                    assertNotNull(tasks.findById(fixture.taskId()).orElseThrow().getArchivedAt());
                    assertTrue(events.existsById(fixture.eventId()));
                }
                case "TRACKS" -> assertEquals("1,2", events.findById(fixture.eventId()).orElseThrow().getInvolvedTrackIds());
                case "REVIEW" -> assertEquals(actor.getId(), reviews.findByEventOrderByRevisionDesc(events.findById(fixture.eventId()).orElseThrow()).get(0).getReviewer().getId());
                case "MANUAL" -> assertEquals(fixture.taskId(), json.readTree(result.getResponse().getContentAsByteArray()).path("taskId").asLong());
                case "PDF" -> assertTrue(result.getResponse().getContentAsByteArray().length > 4);
                default -> { }
            }
        }
    }

    private List<String> operations() {
        return List.of("TASK", "EVENTS", "EVENT", "PDF", "CANCEL", "RETRY", "DELETE", "REVIEW", "TRACKS", "MANUAL");
    }

    private void awaitCompleted(long id) throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (tasks.findById(id).orElseThrow().getStatus() != TaskStatus.SUCCEEDED && System.nanoTime() < deadline) Thread.sleep(10);
        assertEquals(TaskStatus.SUCCEEDED, tasks.findById(id).orElseThrow().getStatus());
    }

    private MockHttpServletRequestBuilder request(String operation, Fixture fixture) throws Exception {
        String task = "/api/tasks/" + fixture.taskId();
        String event = "/api/events/" + fixture.eventId();
        return switch (operation) {
            case "TASK" -> get(task);
            case "EVENTS" -> get(task + "/events");
            case "EVENT" -> get(event);
            case "PDF" -> get(task + "/report");
            case "CANCEL" -> post(task + "/cancel");
            case "RETRY" -> post(task + "/retry");
            case "DELETE" -> delete(task);
            case "REVIEW" -> review(fixture.eventId());
            case "TRACKS" -> patch(event + "/tracks").contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsBytes(Map.of("involvedTrackIds", List.of(1, 2))));
            case "MANUAL" -> manual(fixture.taskId());
            default -> throw new IllegalArgumentException("Unknown fixture operation");
        };
    }

    private MockHttpServletRequestBuilder manual(long taskId) throws Exception {
        return post("/api/events/manual").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(Map.of(
                "taskId", taskId, "eventType", "FOUL", "timestampMs", 1000,
                "summary", "Synthetic permission fixture", "involvedTrackIds", List.of(1, 2))));
    }

    private MockHttpServletRequestBuilder review(long eventId) throws Exception {
        return post("/api/events/" + eventId + "/review").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("result", "UNCERTAIN", "reasonCode", "FIXTURE", "note", "Synthetic review")));
    }

    private MvcResult call(String id, UserAccount actor, MockHttpServletRequestBuilder request, int expected) throws Exception {
        MvcResult result = mvc.perform(request.header("Authorization", "Bearer " + jwt.issue(actor))).andReturn();
        record(id, actor.getDisplayName(), result.getRequest().getMethod(), result.getRequest().getRequestURI(), expected, result.getResponse().getStatus());
        assertEquals(expected, result.getResponse().getStatus(), id);
        return result;
    }

    private void record(String id, String actor, String method, String uri, int expected, int actual) throws IOException {
        String destination = System.getProperty("wbs01.caseLog");
        if (destination != null) {
            Files.writeString(Path.of(destination), json.writeValueAsString(Map.of(
                    "id", id, "identity", actor, "method", method, "uri", uri,
                    "expectedHttp", expected, "actualHttp", actual)) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
    }

    private UserAccount user(String name, Role role) {
        UserAccount user = new UserAccount();
        user.setUsername("fixture_" + name + "_" + UUID.randomUUID());
        user.setDisplayName(name);
        user.setPasswordHash("not-used-for-jwt-fixture");
        user.setRole(role);
        return users.save(user);
    }

    private Fixture fixture(UserAccount assignee) throws IOException {
        VideoAsset video = new VideoAsset();
        video.setTitle("Synthetic ownership fixture");
        video.setCompetition("fixture");
        video.setTeams("fixture");
        video.setOriginalPath(STORAGE.resolve("source.png").toString());
        video.setOwner(owner);
        video.setAssignedTo(assignee);
        video = videos.save(video);
        AnalysisTask task = new AnalysisTask();
        task.setVideo(video);
        task.setOutputDir(Files.createDirectory(STORAGE.resolve("task_" + UUID.randomUUID())).toString());
        task = tasks.save(task);
        DisputeEvent event = new DisputeEvent();
        event.setTask(task);
        event.setExternalId("synthetic_" + UUID.randomUUID());
        event.setEventType(EventType.FOUL);
        event.setRiskLevel(RiskLevel.UNCERTAIN);
        event.setTimestampMs(1000);
        event.setWindowStartMs(0);
        event.setWindowEndMs(2000);
        event.setSummary("Synthetic permission fixture");
        event.setKeyframePath(STORAGE.resolve("source.png").toString());
        event.setClipPath(STORAGE.resolve("source.png").toString());
        event.setInvolvedTrackIds("7,12");
        event.setEvidenceJson("[]");
        event.setTrackBoxesJson("[]");
        event = events.save(event);
        return new Fixture(task.getId(), event.getId());
    }

    private static Path createStorage() {
        try {
            return Files.createTempDirectory("framecall-auth-01-01-");
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    @AfterAll
    static void removeOnlyFixtureStorage() throws IOException {
        Path temp = Path.of(System.getProperty("java.io.tmpdir")).toRealPath();
        if (!STORAGE.toRealPath().getParent().equals(temp) || !STORAGE.getFileName().toString().startsWith("framecall-auth-01-01-")) {
            throw new IllegalStateException("Refusing fixture cleanup outside its temporary directory");
        }
        try (var paths = Files.walk(STORAGE)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    private record Fixture(long taskId, long eventId) { }
}
