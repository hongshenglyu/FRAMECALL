package com.footballref.assist;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.footballref.assist.config.JwtAuthFilter;
import com.footballref.assist.domain.*;
import com.footballref.assist.domain.enums.*;
import com.footballref.assist.repository.*;
import com.footballref.assist.service.*;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.RandomAccessFile;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MediaAccessTest {
    private static final Path WORK = temporaryWork();
    private static final Path STORAGE = WORK.resolve("storage");
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserAccountRepository users;
    @Autowired VideoAssetRepository videos;
    @Autowired AnalysisTaskRepository tasks;
    @Autowired DisputeEventRepository events;
    @Autowired ReviewRecordRepository reviews;
    @Autowired PasswordService passwords;
    @Autowired JwtService jwt;
    @Autowired org.springframework.core.env.Environment environment;
    @Autowired ResourceAccessService access;
    @LocalServerPort int port;
    private UserAccount a, b, admin, assigned;
    private VideoAsset video;
    private DisputeEvent event;
    private Path original, keyframe;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> "jdbc:h2:mem:media_" + WORK.getFileName() + ";DB_CLOSE_DELAY=-1");
        properties.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        properties.add("spring.datasource.username", () -> "wbs_fixture");
        properties.add("spring.datasource.password", () -> "");
        properties.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        properties.add("app.storage-root", STORAGE::toString);
    }

    @BeforeEach
    void fixture() throws Exception {
        reviews.deleteAll(); events.deleteAll(); tasks.deleteAll(); videos.deleteAll(); users.deleteAll();
        a = user("media-owner", Role.REFEREE); b = user("media-other", Role.REFEREE);
        admin = user("media-admin", Role.ADMIN); assigned = user("media-assigned", Role.REFEREE);
        original = Files.createDirectories(STORAGE.resolve("uploads")).resolve("synthetic.mp4");
        String mp4 = System.getProperty("wbs02.mp4");
        // HTTP unit fixtures do not themselves claim successful codec playback.
        if (mp4 == null) Files.write(original, new byte[4096]);
        else Files.copy(Path.of(mp4), original, StandardCopyOption.REPLACE_EXISTING);
        video = new VideoAsset(); video.setTitle("Synthetic media fixture"); video.setCompetition("fixture");
        video.setTeams("fixture A / B"); video.setOwner(a); video.setAssignedTo(assigned);
        video.setOriginalPath(original.toString()); video = videos.save(video);
        AnalysisTask task = new AnalysisTask(); task.setVideo(video); task.setStatus(TaskStatus.SUCCEEDED);
        task.setStage("COMPLETED"); task.setSensitivity(Sensitivity.BALANCED); task = tasks.save(task);
        Path directory = Files.createDirectories(STORAGE.resolve("tasks/task-" + task.getId()));
        task.setOutputDir(directory.toString()); task = tasks.save(task);
        keyframe = directory.resolve("keyframe.png");
        ImageIO.write(new BufferedImage(80, 60, BufferedImage.TYPE_INT_RGB), "png", keyframe.toFile());
        event = new DisputeEvent(); event.setTask(task); event.setExternalId("SYNTHETIC");
        event.setEventType(EventType.FOUL); event.setRiskLevel(RiskLevel.UNCERTAIN);
        event.setSummary("Synthetic playback fixture, no referee decision"); event.setInvolvedTrackIds("1,2");
        event.setKeyframePath(keyframe.toString()); event.setClipPath(original.toString());
        event.setEvidenceJson("[]"); event.setTrackBoxesJson("[]"); event = events.save(event);
    }

    @Test
    void anonymousCannotReadKnownMediaOrUseHeadOptionsOrCookieWrites() throws Exception {
        for (String url : mediaUrls()) {
            call("ANONYMOUS_GET", null, get(url), 401);
            call("ANONYMOUS_HEAD", null, head(url), 401);
            call("ANONYMOUS_OPTIONS", null, options(url), 401);
        }
        Cookie cookie = new Cookie(JwtAuthFilter.MEDIA_COOKIE, jwt.issue(a));
        call("COOKIE_CANNOT_WRITE", null, post("/api/files/session").cookie(cookie), 401);
        call("COOKIE_CANNOT_READ_TASK", null, get("/api/tasks").cookie(cookie), 401);
    }

    @Test
    void persistedOwnershipAllowsOwnerAdminAssignedAndDeniesBothDirections() throws Exception {
        for (String url : mediaUrls()) {
            for (UserAccount actor : List.of(a, admin, assigned)) {
                MvcResult result = call("ALLOW_MEDIA", actor, get(url), 200);
                byte[] expected = url.contains("keyframe") ? Files.readAllBytes(keyframe) : Files.readAllBytes(original);
                assertArrayEquals(expected, result.getResponse().getContentAsByteArray());
            }
            call("DENY_B", b, get(url), 404);
        }
        video.setOwner(b); video.setAssignedTo(null); videos.save(video);
        call("DENY_A_ON_B", a, get(videoUrl()), 404);
        call("ALLOW_NEW_OWNER_B", b, get(videoUrl()), 200);
    }

    @Test
    void rangesDeliverExactSegmentsAndRejectInvalidOrMultipleRanges() throws Exception {
        byte[] bytes = Files.readAllBytes(original); int length = bytes.length;
        String[] ranges = {"bytes=0-31", "bytes=32-", "bytes=-16"};
        int[] starts = {0, 32, length - 16}; int[] ends = {31, length - 1, length - 1};
        for (int i = 0; i < ranges.length; i++) {
            var response = call("RANGE_" + i, a, get(videoUrl()).header("Range", ranges[i]), 206).getResponse();
            assertArrayEquals(Arrays.copyOfRange(bytes, starts[i], ends[i] + 1), response.getContentAsByteArray());
            assertEquals("bytes " + starts[i] + "-" + ends[i] + "/" + length, response.getHeader("Content-Range"));
            assertEquals(Integer.toString(ends[i] - starts[i] + 1), response.getHeader("Content-Length"));
            assertEquals("bytes", response.getHeader("Accept-Ranges"));
            assertEquals("video/mp4", response.getContentType());
        }
        for (String range : List.of("bytes=" + length + "-", "bytes=5-1", "bytes=-0", "bytes=bad", "bytes=0-1,4-5", "bytes=", "items=0-1")) {
            var response = call("INVALID_RANGE", a, get(videoUrl()).header("Range", range), 416).getResponse();
            assertEquals("bytes */" + length, response.getHeader("Content-Range"));
            assertEquals(0, response.getContentAsByteArray().length);
        }
        call("B_CANNOT_RANGE", b, get(videoUrl()).header("Range", "bytes=0-31"), 404);
    }

    @Test
    void expiredCookiesDemotedAdminsAndRemovedAssignmentsCannotRead() throws Exception {
        String[] parts = jwt.issue(a).split("\\.");
        var payload = json.readValue(Base64.getUrlDecoder().decode(parts[1]), Map.class);
        payload.put("exp", 1);
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(payload));
        String data = parts[0] + "." + encoded;
        var mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(environment.getRequiredProperty("app.jwt-secret").getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
        String expired = data + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(data.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        call("EXPIRED_MEDIA_COOKIE", null, get(videoUrl()).cookie(new Cookie(JwtAuthFilter.MEDIA_COOKIE, expired)), 401);
        Cookie stale = new Cookie(JwtAuthFilter.MEDIA_COOKIE, jwt.issue(admin));
        admin.setRole(Role.REFEREE); users.save(admin);
        call("DEMOTED_ADMIN_COOKIE", null, get(videoUrl()).cookie(stale), 404);
        video.setAssignedTo(null); videos.save(video);
        call("REMOVED_ASSIGNMENT", assigned, get(videoUrl()), 404);
        video.setOwner(null);
        assertFalse(access.canAccessVideo(video, a)); assertFalse(access.canAccessVideo(video, admin));
        assertFalse(access.canAccessVideo(null, admin));
    }

    @Test
    void controlledRelativeStorageAliasesAndWindowsCaseKeepAuthorizedMediaPlayable() throws Exception {
        List<String> paths = new ArrayList<>(List.of("uploads/synthetic.mp4", "storage/uploads/synthetic.mp4", "/storage/uploads/synthetic.mp4"));
        if (System.getProperty("os.name").startsWith("Windows")) paths.add(original.toString().toUpperCase(Locale.ROOT));
        for (String path : paths) {
            video.setOriginalPath(path); videos.save(video);
            call("SUPPORTED_STORAGE_ALIAS", a, get(videoUrl()).header("Range", "bytes=0-15"), 206);
        }
    }

    @Test
    void cookieBridgeIsHttpOnlyScopedSecureAndRechecksDatabase() throws Exception {
        var session = call("SESSION_CREATE", a, post("/api/files/session").secure(true), 204).getResponse();
        String header = session.getHeader("Set-Cookie");
        for (String flag : List.of("HttpOnly", "Path=/api/files", "SameSite=Strict", "Secure")) assertTrue(header.contains(flag));
        Cookie cookie = new Cookie(JwtAuthFilter.MEDIA_COOKIE, jwt.issue(a));
        var response = call("COOKIE_PLAY", null, get(videoUrl()).cookie(cookie), 200).getResponse();
        assertEquals("no-store", response.getHeader("Cache-Control"));
        call("INVALID_BEARER_NO_COOKIE_FALLBACK", null, get(videoUrl()).cookie(cookie).header("Authorization", "Bearer invalid"), 401);
        a.setEnabled(false); users.save(a);
        call("DISABLED_COOKIE", null, get(videoUrl()).cookie(cookie), 401);
        a.setEnabled(true); users.save(a);
        video.setOwner(b); video.setAssignedTo(null); videos.save(video);
        call("REASSIGNED_COOKIE", null, get(videoUrl()).cookie(cookie), 404);
        String clear = call("SESSION_CLEAR", a, delete("/api/files/session"), 204).getResponse().getHeader("Set-Cookie");
        assertTrue(clear.contains("Max-Age=0"));
    }

    @Test
    void pathParametersAndUnknownResourcesNeverRevealBytesOrPaths() throws Exception {
        for (String value : List.of(original.toString(), "../outside.mp4", "%2e%2e%2foutside.mp4", "%252e%252e%255coutside.mp4",
                "C:\\Windows\\win.ini", "\\\\server\\share\\video.mp4", "uploads/a\0.mp4")) {
            call("LEGACY_PATH_REJECTED", a, get("/api/files").param("path", value), 404);
        }
        for (String url : List.of("/api/files?videoId=9223372036854775807", "/api/files?videoId=bad",
                "/api/files?eventId=" + event.getId() + "&kind=other", videoUrl() + "&path=x")) {
            call("MISSING_OR_MALFORMED", a, get(url), 404);
        }
        Files.delete(original);
        call("MISSING_FILE", a, get(videoUrl()), 404);
        call("NO_PERMISSION_MISSING_FILE", b, get(videoUrl()), 404);
    }

    @Test
    void storedTraversalAliasesOutsideRootsAndActiveContentFailClosed() throws Exception {
        Path outside = Files.write(WORK.resolve("outside.mp4"), new byte[]{99, 98});
        Path similar = Files.createDirectories(WORK.resolve("storage-lookalike")).resolve("video.mp4");
        Files.write(similar, new byte[]{99, 98});
        List<String> invalid = List.of(outside.toString(), similar.toString(), STORAGE.resolve("uploads/../uploads/synthetic.mp4").toString(),
                "uploads/%252e%252e/outside.mp4", "uploads/synthetic.mp4:stream", "uploads/synthetic.mp4.",
                "uploads/synthetic.mp4 ", "\\\\?\\C:\\video.mp4", "uploads/SYNTH~1.MP4", "uploads/a\0.mp4");
        for (String path : invalid) {
            video.setOriginalPath(path); videos.save(video);
            call("STORED_PATH_REJECTED", a, get(videoUrl()), 404);
        }
        Path active = Files.writeString(STORAGE.resolve("uploads/active.svg"), "<svg/>");
        video.setOriginalPath(active.toString()); videos.save(video);
        call("ACTIVE_CONTENT_REJECTED", a, get(videoUrl()), 404);
    }

    @Test
    void eventCannotBorrowAnotherTasksMediaEvenInsideRoot() throws Exception {
        Path unrelated = Files.createDirectories(STORAGE.resolve("tasks/task-999999")).resolve("other.png");
        Files.copy(keyframe, unrelated, StandardCopyOption.REPLACE_EXISTING);
        event.setKeyframePath(unrelated.toString()); events.save(event);
        call("CROSS_TASK_MEDIA", a, get(eventUrl("keyframe")), 404);
    }

    @Test
    void windowsJunctionEscapeIsRejectedWithoutReadingTarget() throws Exception {
        Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        Path target = Files.createDirectories(WORK.resolve("junction-target-" + UUID.randomUUID()));
        Files.write(target.resolve("secret.mp4"), new byte[]{99, 98});
        Path link = STORAGE.resolve("junction-" + UUID.randomUUID());
        Process process = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                .redirectErrorStream(true).start();
        assertTrue(process.waitFor(10, TimeUnit.SECONDS)); assertEquals(0, process.exitValue(), "Test junction could not be created");
        try {
            video.setOriginalPath(link.resolve("secret.mp4").toString()); videos.save(video);
            call("JUNCTION_ESCAPE", a, get(videoUrl()), 404);
        } finally { Files.delete(link); }
    }

    @Test
    void symbolicLinkEscapeIsRejectedWhenPlatformPermitsCreatingLink() throws Exception {
        Path target = Files.write(WORK.resolve("symlink-target.mp4"), new byte[]{99, 98});
        Path link = STORAGE.resolve("symlink-" + UUID.randomUUID() + ".mp4");
        try { Files.createSymbolicLink(link, target); }
        catch (Exception unavailable) {
            record(Map.of("id", "SYMLINK", "outcome", "NOT_RUN", "reason", "Platform denied symbolic-link creation; junction test is separate evidence"));
            Assumptions.assumeTrue(false, "Platform denied symbolic-link creation");
        }
        try {
            video.setOriginalPath(link.toString()); videos.save(video);
            call("SYMLINK_ESCAPE", a, get(videoUrl()), 404);
        } finally { Files.deleteIfExists(link); }
    }

    @Test
    void sparseLargeVideoCanServeSuffixWithoutMaterializingWholeFile() throws Exception {
        try (RandomAccessFile file = new RandomAccessFile(original.toFile(), "rw")) {
            file.setLength(256L * 1024 * 1024); file.seek(file.length() - 4); file.write(new byte[]{3, 4, 5, 6});
        }
        var response = call("LARGE_SUFFIX", a, get(videoUrl()).header("Range", "bytes=-4"), 206).getResponse();
        assertArrayEquals(new byte[]{3, 4, 5, 6}, response.getContentAsByteArray());
        assertEquals("4", response.getHeader("Content-Length"));
    }

    @Test
    void pdfCannotEmbedOutsideMediaAndCannotWriteOutsideStorage() throws Exception {
        Path outside = WORK.resolve("outside-preview.png"); Files.copy(keyframe, outside, StandardCopyOption.REPLACE_EXISTING);
        event.setKeyframePath(outside.toString()); events.save(event);
        var response = call("PDF_SAFE_PREVIEW", a, get("/api/tasks/" + event.getTask().getId() + "/report"), 200).getResponse();
        try (com.lowagie.text.pdf.PdfReader reader = new com.lowagie.text.pdf.PdfReader(response.getContentAsByteArray())) {
            for (int i = 0; i < reader.getXrefSize(); i++) {
                var item = reader.getPdfObject(i);
                if (item instanceof com.lowagie.text.pdf.PdfDictionary dictionary) {
                    assertNotEquals(com.lowagie.text.pdf.PdfName.IMAGE, dictionary.getAsName(com.lowagie.text.pdf.PdfName.SUBTYPE));
                }
            }
        }
        AnalysisTask task = tasks.findById(event.getTask().getId()).orElseThrow(); task.setOutputDir(WORK.toString()); tasks.save(task);
        call("PDF_OUTPUT_BOUNDARY", a, get("/api/tasks/" + task.getId() + "/report"), 404);
        assertFalse(Files.exists(WORK.resolve("referee-review-report.pdf")));
    }

    @Test
    void actualBrowserLoginPlaybackSeekPauseRefreshAndOwnership() throws Exception {
        String script = System.getProperty("wbs02.browser");
        Assumptions.assumeTrue(script != null, "Browser runner not configured");
        assertNotNull(System.getProperty("wbs02.mp4"), "Browser evidence requires generated decodable MP4");
        String password = UUID.randomUUID().toString();
        for (UserAccount actor : List.of(a, b, admin)) { actor.setPasswordHash(passwords.hash(password)); users.save(actor); }
        Path input = WORK.resolve("browser-input.json");
        json.writeValue(input.toFile(), Map.of("backend", "http://127.0.0.1:" + port, "password", password,
                "owner", a.getUsername(), "other", b.getUsername(), "admin", admin.getUsername(),
                "videoId", video.getId(), "eventId", event.getId()));
        Path log = Path.of(script).getParent().resolve("browser.raw.log");
        Process process = new ProcessBuilder("node", script, input.toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        boolean ended = process.waitFor(100, TimeUnit.SECONDS);
        if (!ended) process.destroyForcibly();
        assertTrue(ended, "Browser deadline exceeded; inspect local sanitized log");
        assertEquals(0, process.exitValue(), "Browser runner failed; inspect local sanitized log");
    }

    private UserAccount user(String name, Role role) {
        UserAccount user = new UserAccount(); user.setUsername(name); user.setDisplayName(name);
        user.setRole(role); user.setEnabled(true); user.setPasswordHash("fixture-unusable"); return users.save(user);
    }
    private String videoUrl() { return "/api/files?videoId=" + video.getId(); }
    private String eventUrl(String kind) { return "/api/files?eventId=" + event.getId() + "&kind=" + kind; }
    private List<String> mediaUrls() { return List.of(videoUrl(), eventUrl("keyframe"), eventUrl("clip")); }

    private MvcResult call(String id, UserAccount actor, MockHttpServletRequestBuilder request, int expected) throws Exception {
        if (actor != null) request.header("Authorization", "Bearer " + jwt.issue(actor));
        MvcResult result = mvc.perform(request).andReturn(); int actual = result.getResponse().getStatus();
        record(Map.of("id", id, "identity", actor == null ? "anonymous-or-cookie" : actor.getDisplayName(),
                "method", result.getRequest().getMethod(), "endpoint", result.getRequest().getRequestURI(),
                "expectedHttp", expected, "actualHttp", actual, "outcome", expected == actual ? "PASS" : "FAIL"));
        assertEquals(expected, actual, id);
        if (expected >= 400) {
            assertEquals(0, result.getResponse().getContentAsByteArray().length, "Denied response must not reveal bytes");
            assertNull(result.getResponse().getHeader("Content-Disposition"));
        }
        return result;
    }
    private void record(Map<String, Object> value) throws Exception {
        String output = System.getProperty("wbs02.evidence");
        if (output != null) Files.writeString(Path.of(output), json.writeValueAsString(value) + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
    private static Path temporaryWork() {
        try { return Files.createTempDirectory("framecall-media-01-02-"); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }
}
