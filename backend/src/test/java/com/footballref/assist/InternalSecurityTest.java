package com.footballref.assist;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.footballref.assist.domain.*;
import com.footballref.assist.domain.enums.*;
import com.footballref.assist.repository.*;
import com.footballref.assist.service.*;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class InternalSecurityTest {
    private static final Path WORK = temporary();
    private static final Path ROOT = WORK.resolve("storage");
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired InternalCallbackService callbacks;
    @Autowired StorageService storage;
    @Autowired Environment environment;
    @Autowired AnalysisTaskRepository tasks;
    @Autowired VideoAssetRepository videos;
    @Autowired UserAccountRepository users;
    @Autowired JwtService jwt;
    @LocalServerPort int port;
    AnalysisTask task;
    UserAccount owner;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> "jdbc:h2:mem:internal_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        properties.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        properties.add("spring.datasource.username", () -> "wbs_fixture");
        properties.add("spring.datasource.password", () -> "");
        properties.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        properties.add("app.storage-root", ROOT::toString);
    }

    @BeforeEach
    void fixture() throws Exception {
        owner = new UserAccount(); owner.setUsername("internal_" + UUID.randomUUID()); owner.setDisplayName("internal-fixture");
        owner.setPasswordHash("unusable"); owner.setRole(Role.ADMIN); owner.setEnabled(true); owner = users.save(owner);
        Path videoPath = Files.createDirectories(ROOT.resolve("uploads")).resolve(UUID.randomUUID() + ".mp4"); Files.write(videoPath, new byte[]{1, 2, 3});
        VideoAsset video = new VideoAsset(); video.setOwner(owner); video.setTitle("synthetic internal"); video.setCompetition("fixture");
        video.setTeams("fixture"); video.setOriginalPath(videoPath.toString()); video = videos.save(video);
        task = new AnalysisTask(); task.setVideo(video); task.setStatus(TaskStatus.PENDING); task.setStage("PENDING");
        task.setSensitivity(Sensitivity.BALANCED); task = tasks.save(task);
        task.setOutputDir(Files.createDirectories(ROOT.resolve("tasks/task-" + task.getId())).toString()); task = tasks.save(task);
    }

    @Test
    void ordinaryIdentitiesAndMalformedAnonymousBodiesCannotReachProgress() throws Exception {
        String path = path(task.getId()); String bearer = jwt.issue(owner);
        check("NO_SERVICE", mvc.perform(post(path).contentType("application/json").content("not-json")).andReturn().getResponse().getStatus(), 401);
        for (String token : List.of("wrong", environment.getRequiredProperty("app.internal-token"))) {
            check("STATIC_OR_BAD_CALLBACK", mvc.perform(post(path).header("X-Internal-Token", token).contentType("application/json").content(body(24))).andReturn().getResponse().getStatus(), 401);
        }
        check("USER_JWT_NOT_SERVICE", mvc.perform(post(path).header("Authorization", "Bearer " + bearer).contentType("application/json").content(body(24))).andReturn().getResponse().getStatus(), 401);
        check("MEDIA_COOKIE_NOT_SERVICE", mvc.perform(post(path).cookie(new Cookie("framecall_media", bearer)).contentType("application/json").content(body(24))).andReturn().getResponse().getStatus(), 401);
        assertEquals(0, tasks.findById(task.getId()).orElseThrow().getProgressPercent());
    }

    @Test
    void currentGrantIsTaskScopedAndOldOrRevokedInvocationCannotUpdate() throws Exception {
        String old = callbacks.issue(task.getId());
        call("VALID_CALLBACK", task.getId(), old, body(24), 200);
        assertEquals(24, tasks.findById(task.getId()).orElseThrow().getProgressPercent());
        call("CROSS_TASK_GRANT", task.getId() + 1, old, body(30), 401);
        String current = callbacks.issue(task.getId());
        call("STALE_INVOCATION", task.getId(), old, body(35), 401);
        callbacks.revoke(task.getId(), old);
        call("OLD_COMPLETION_CANNOT_REVOKE_NEW", task.getId(), current, body(40), 200);
        callbacks.revoke(task.getId(), current);
        call("REVOKED_CALLBACK", task.getId(), current, body(50), 401);
        assertEquals(40, tasks.findById(task.getId()).orElseThrow().getProgressPercent());
    }

    @Test
    void authenticatedProgressCannotInjectStageOrOutOfBoundsPercent() throws Exception {
        String token = callbacks.issue(task.getId());
        call("INVALID_PERCENT", task.getId(), token, body(101), 400);
        call("INVALID_STAGE", task.getId(), token, "{\"stage\":\"UNSAFE\",\"progressPercent\":10}", 400);
        assertEquals("PENDING", tasks.findById(task.getId()).orElseThrow().getStage());
        callbacks.revoke(task.getId(), token);
    }

    @Test
    void grantsExpireWithoutPretendingToImplementDurableAttempts() {
        MutableClock clock = new MutableClock(); InternalCallbackService local = new InternalCallbackService(clock);
        String token = local.issue(1L); assertTrue(local.permits(1L, token));
        clock.now = clock.now.plus(Duration.ofHours(3)); assertFalse(local.permits(1L, token));
    }

    @Test
    void outboundClientUsesDistinctServiceHeadersAndFixedCallbackThenRevokesGrant() throws Exception {
        String service = UUID.randomUUID() + UUID.randomUUID().toString();
        AtomicReference<String> delivered = new AtomicReference<>(); AtomicReference<Map> payload = new AtomicReference<>();
        AtomicReference<Boolean> validWhileRunning = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/analyze", exchange -> {
            String grant = exchange.getRequestHeaders().getFirst("X-Callback-Token");
            delivered.set(grant); validWhileRunning.set(callbacks.permits(task.getId(), grant));
            assertEquals(service, exchange.getRequestHeaders().getFirst("X-AI-Service-Token"));
            payload.set(json.readValue(exchange.getRequestBody(), Map.class));
            byte[] response = "{\"events\":[],\"status\":\"SUCCEEDED\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response); exchange.close();
        }); server.start();
        try {
            AiClient client = new AiClient("http://127.0.0.1:" + server.getAddress().getPort(), service, "http://127.0.0.1:1", storage, callbacks);
            assertEquals("SUCCEEDED", client.analyze(task, "file:///ignored-client-url").get("status"));
            assertEquals("http://127.0.0.1:1/api/internal/tasks/" + task.getId() + "/progress", payload.get().get("callbackUrl"));
            assertFalse(payload.get().containsKey("internalToken")); assertTrue(validWhileRunning.get());
            assertFalse(callbacks.permits(task.getId(), delivered.get()));
        } finally { server.stop(0); }
    }

    @Test
    void invalidOutputAndOriginsDoNotReachNetworkAndRedirectDoesNotReachTrap() throws Exception {
        AtomicInteger calls = new AtomicInteger(); AtomicInteger traps = new AtomicInteger();
        HttpServer trap = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        trap.createContext("/", e -> { traps.incrementAndGet(); e.sendResponseHeaders(200, -1); e.close(); }); trap.start();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", e -> { calls.incrementAndGet(); e.getResponseHeaders().set("Location", "http://127.0.0.1:" + trap.getAddress().getPort() + "/sensitive"); e.sendResponseHeaders(302, -1); e.close(); }); server.start();
        String service = UUID.randomUUID() + UUID.randomUUID().toString();
        AiClient client = new AiClient("http://127.0.0.1:" + server.getAddress().getPort(), service, "http://127.0.0.1:1", storage, callbacks);
        try {
            String original = task.getOutputDir(); task.setOutputDir(WORK.toString());
            assertThrows(IllegalArgumentException.class, () -> client.analyze(task, "ignored")); assertEquals(0, calls.get()); task.setOutputDir(original);
            AiClient unsafe = new AiClient("http://127.0.0.1:" + server.getAddress().getPort(), service, "http://rebind.test:9", storage, callbacks);
            assertThrows(IllegalStateException.class, () -> unsafe.analyze(task, "ignored")); assertEquals(0, calls.get());
            assertThrows(IllegalStateException.class, () -> client.analyze(task, "ignored"));
            assertEquals(1, calls.get()); assertEquals(0, traps.get());
            assertThrows(IllegalStateException.class, () -> client.cancel(task.getId())); assertEquals(0, traps.get());
        } finally { server.stop(0); trap.stop(0); }
    }

    @Test
    void actualJavaToFastApiToSpringProgressChainUsesIsolatedStorageAndH2() throws Exception {
        String python = System.getProperty("wbs03.python");
        Assumptions.assumeTrue(python != null, "Isolated AI venv path not supplied");
        int aiPort; try (java.net.ServerSocket socket = new java.net.ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) { aiPort = socket.getLocalPort(); }
        String service = UUID.randomUUID() + UUID.randomUUID().toString(); String origin = "http://127.0.0.1:" + aiPort;
        ProcessBuilder builder = new ProcessBuilder(python, "-B", "-m", "uvicorn", "app.main:app", "--host", "127.0.0.1", "--port", Integer.toString(aiPort), "--no-access-log", "--log-level", "critical");
        builder.directory(Path.of(System.getProperty("wbs03.aiDir")).toFile());
        builder.environment().putAll(Map.of("AI_SERVICE_TOKEN", service, "AI_STORAGE_ROOT", ROOT.toString(),
                "AI_CALLBACK_BASE_URL", "http://127.0.0.1:" + port, "AI_ANALYZER_MODE", "mock", "AI_TRACKER_MODE", "mock", "AI_YOLO_ALLOW_DOWNLOAD", "false", "PYTHONDONTWRITEBYTECODE", "1"));
        builder.redirectErrorStream(true).redirectOutput(WORK.resolve("bridge.raw.log").toFile());
        Process process = builder.start();
        try {
            var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build(); boolean ready = false;
            for (int i = 0; i < 100; i++) {
                try { ready = http.send(HttpRequest.newBuilder(URI.create(origin + "/health")).timeout(Duration.ofSeconds(1)).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode() == 200; }
                catch (Exception ignored) { }
                if (ready) break; Thread.sleep(50);
            }
            assertTrue(ready, "Isolated AI server did not start");
            AiClient client = new AiClient(origin, service, "http://127.0.0.1:" + port, storage, callbacks);
            var result = client.analyze(task, "ignored"); assertEquals("SUCCEEDED", result.get("status"));
            AnalysisTask updated = tasks.findById(task.getId()).orElseThrow(); assertEquals(99, updated.getProgressPercent());
            assertEquals("COMPLETED", updated.getStage()); assertEquals(TaskStatus.PENDING, updated.getStatus());
            assertTrue(Files.isRegularFile(Path.of(task.getOutputDir()).resolve("analysis_result.json")));
            String evidence = System.getProperty("wbs03.evidence");
            if (evidence != null) Files.writeString(Path.of(evidence), json.writeValueAsString(Map.of(
                    "id", "JAVA_FASTAPI_SPRING_BRIDGE", "layer", "ACTUAL_CHAIN", "outcome", "PASS",
                    "progress", updated.getProgressPercent(), "stage", updated.getStage(), "httpStatus", "NOT_CAPTURED")) + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } finally { process.destroy(); if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly(); }
    }

    private String path(Long id) { return "/api/internal/tasks/" + id + "/progress"; }
    private String body(int percent) { return "{\"stage\":\"DETECTING\",\"progressPercent\":" + percent + "}"; }
    private void call(String id, Long taskId, String token, String body, int expected) throws Exception {
        int actual = mvc.perform(post(path(taskId)).header("X-Internal-Token", token).contentType("application/json").content(body)).andReturn().getResponse().getStatus();
        check(id, actual, expected);
    }
    private void check(String id, int actual, int expected) throws Exception {
        String evidence = System.getProperty("wbs03.evidence");
        if (evidence != null) Files.writeString(Path.of(evidence), json.writeValueAsString(Map.of("id", id, "expected", expected, "actual", actual, "outcome", actual == expected ? "PASS" : "FAIL")) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        assertEquals(expected, actual, id);
    }
    private static Path temporary() {
        try { return Files.createTempDirectory("framecall-internal-java-"); } catch (Exception e) { throw new IllegalStateException(e); }
    }
    private static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-24T00:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
}
