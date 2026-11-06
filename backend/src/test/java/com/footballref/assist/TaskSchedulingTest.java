package com.footballref.assist;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.footballref.assist.domain.*;
import com.footballref.assist.domain.enums.*;
import com.footballref.assist.repository.*;
import com.footballref.assist.service.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TaskSchedulingTest {
    private static final Path ROOT = temporary();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AtomicInteger CALLS = new AtomicInteger();
    private static final AtomicReference<String> MODE = new AtomicReference<>("ok");
    private static final AtomicReference<CountDownLatch> RELEASE = new AtomicReference<>(new CountDownLatch(0));
    private static final AtomicReference<CountDownLatch> ENTERED = new AtomicReference<>(new CountDownLatch(1));
    private static final HttpServer AI = fakeAi();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    @LocalServerPort int port;
    @Autowired AnalysisTaskRepository tasks;
    @Autowired TaskAttemptRepository attempts;
    @Autowired VideoAssetRepository videos;
    @Autowired UserAccountRepository users;
    @Autowired DisputeEventRepository events;
    @Autowired ReviewRecordRepository reviews;
    @Autowired TaskService service;
    @Autowired AnalysisDispatcher dispatcher;
    @Autowired JwtService jwt;
    @Autowired InternalCallbackService callbacks;
    @SpyBean AiClient aiClient;
    UserAccount owner,other,admin;
    final List<String> workerThreads = new CopyOnWriteArrayList<>();

    @DynamicPropertySource static void configuration(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", () -> "jdbc:h2:mem:schedule_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        p.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        p.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        p.add("app.storage-root", ROOT::toString);
        p.add("app.ai-service-url", () -> "http://127.0.0.1:" + AI.getAddress().getPort());
        p.add("app.analysis.workers", () -> 1);
        p.add("app.analysis.queue-capacity", () -> 1);
        p.add("app.analysis.read-timeout-ms", () -> 1200);
    }

    @BeforeEach void setup() throws Exception {
        CALLS.set(0); MODE.set("ok"); RELEASE.set(new CountDownLatch(0)); ENTERED.set(new CountDownLatch(1));
        owner=user(Role.REFEREE); other=user(Role.REFEREE); admin=user(Role.ADMIN);
        doAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "AI I/O must not hold a DB transaction");
            workerThreads.add(Thread.currentThread().getName());
            return call.callRealMethod();
        }).when(aiClient).analyze(any(), anyString());
    }
    @AfterEach void release() throws Exception {
        RELEASE.get().countDown();
        await(() -> dispatcher.queued() == 0 && attempts.findByActiveTrue().stream()
                .allMatch(a -> Set.of("RECOVERY_REQUIRED","AI_OUTCOME_UNKNOWN").contains(a.getErrorCode() == null ? "" : a.getErrorCode())));
    }
    @AfterAll static void stop() { AI.stop(0); }

    @Test void twentyConcurrentRetriesHaveOneDatabaseAttemptAndOneActualExecution() throws Exception {
        AnalysisTask task=task(TaskStatus.FAILED); hold();
        ExecutorService clients=Executors.newFixedThreadPool(20);
        CountDownLatch ready=new CountDownLatch(20), start=new CountDownLatch(1);
        List<Future<Integer>> results=new ArrayList<>();
        try {
            for(int i=0;i<20;i++) results.add(clients.submit(() -> { ready.countDown(); start.await(); return retry(task,owner).statusCode(); }));
            assertTrue(ready.await(5,TimeUnit.SECONDS)); start.countDown();
            List<Integer> codes=new ArrayList<>(); for(Future<Integer> f:results) codes.add(f.get(10,TimeUnit.SECONDS));
            assertEquals(1,Collections.frequency(codes,200)); assertEquals(19,Collections.frequency(codes,409));
            assertTrue(ENTERED.get().await(3,TimeUnit.SECONDS));
            assertEquals(1,attempts.countByTaskIdAndActiveTrue(task.getId())); assertEquals(1,CALLS.get());
            assertEquals(1,workerThreads.size()); assertTrue(workerThreads.get(0).startsWith("analysis-worker-"));
            evidence("20_CONCURRENT_RETRIES",Map.of("requests",20,"accepted",1,"conflict",19,"activeAttempts",1,"aiCalls",CALLS.get(),"worker",workerThreads.get(0)));
        } finally { start.countDown(); clients.shutdownNow(); RELEASE.get().countDown(); }
        completed(task,TaskStatus.SUCCEEDED);
        assertEquals(1,events.findByTaskOrderByTimestampMsAsc(task).size());
        assertEquals(409,retry(task,owner).statusCode());
        assertEquals(1,attempts.findByTaskIdOrderByCreatedAtAsc(task.getId()).size());
    }

    @Test void uploadAndRetryReturnWhileRealAiIsBlockedAndProgressRemainsQueryable() throws Exception {
        hold(); String boundary="wbs05_"+UUID.randomUUID();
        String body="--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"synthetic.mp4\"\r\nContent-Type: video/mp4\r\n\r\nsynthetic\r\n";
        for(String field:List.of("title","competition","teams")) body+="--"+boundary+"\r\nContent-Disposition: form-data; name=\""+field+"\"\r\n\r\nfixture\r\n";
        body+="--"+boundary+"--\r\n";
        long start=System.nanoTime();
        var response=send("/api/videos",owner,"multipart/form-data; boundary="+boundary,body);
        long ms=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);
        assertEquals(200,response.statusCode()); long id=JSON.readTree(response.body()).path("task").path("id").asLong();
        assertTrue(ENTERED.get().await(3,TimeUnit.SECONDS)); assertEquals(1,RELEASE.get().getCount());
        assertTrue(ms<1000,"Request should return before AI timeout");
        AnalysisTask task=tasks.findById(id).orElseThrow(); assertEquals(TaskStatus.RUNNING,task.getStatus());
        var get=HTTP.send(HttpRequest.newBuilder(URI.create(base()+"/api/tasks/"+id)).header("Authorization","Bearer "+jwt.issue(owner)).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(200,get.statusCode()); assertEquals("RUNNING",JSON.readTree(get.body()).path("status").asText());
        evidence("UPLOAD_RETURNS_BEFORE_AI",Map.of("http",200,"milliseconds",ms,"worker",workerThreads.get(0),"requestThread",Thread.currentThread().getName(),"readHttp",get.statusCode()));
        RELEASE.get().countDown(); completed(task,TaskStatus.SUCCEEDED);
        AnalysisTask retryable=task(TaskStatus.CANCELLED); hold(); start=System.nanoTime();
        assertEquals(200,retry(retryable,owner).statusCode()); ms=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);
        assertTrue(ms<1000); assertTrue(ENTERED.get().await(3,TimeUnit.SECONDS)); assertEquals(1,RELEASE.get().getCount());
        evidence("CANCELLED_RETRY_RETURNS_BEFORE_AI",Map.of("http",200,"milliseconds",ms));
        RELEASE.get().countDown(); completed(retryable,TaskStatus.SUCCEEDED);
    }

    @Test void boundedQueueRejectsWithoutPhantomSuccessAndLaterRetryWorks() throws Exception {
        hold(); AnalysisTask a=task(TaskStatus.FAILED),b=task(TaskStatus.FAILED),c=task(TaskStatus.FAILED);
        assertEquals(200,retry(a,owner).statusCode()); assertTrue(ENTERED.get().await(3,TimeUnit.SECONDS));
        assertEquals(200,retry(b,owner).statusCode()); assertEquals(1,dispatcher.queued());
        assertEquals(503,retry(c,owner).statusCode()); completed(c,TaskStatus.FAILED);
        assertEquals("DISPATCH_REJECTED",tasks.findById(c.getId()).orElseThrow().getErrorMessage());
        assertEquals(0,attempts.countByTaskIdAndActiveTrue(c.getId()));
        evidence("QUEUE_REJECTED",Map.of("http",503,"queue",dispatcher.queued(),"activeRejected",0));
        RELEASE.get().countDown(); completed(a,TaskStatus.SUCCEEDED); completed(b,TaskStatus.SUCCEEDED);
        assertEquals(200,retry(c,owner).statusCode()); completed(c,TaskStatus.SUCCEEDED);
        assertEquals(2,attempts.findByTaskIdOrderByCreatedAtAsc(c.getId()).size());
    }

    @Test void failuresAreSanitizedBoundedAndNeverAutoRetried() throws Exception {
        for(String mode:List.of("503","disconnect","bad","hold")) {
            MODE.set(mode); RELEASE.set(new CountDownLatch(mode.equals("hold")?1:0));
            AnalysisTask task=task(TaskStatus.FAILED); int before=CALLS.get();
            assertEquals(200,retry(task,owner).statusCode()); completed(task,TaskStatus.FAILED);
            boolean uncertain=mode.equals("disconnect")||mode.equals("hold");
            assertEquals(uncertain?"AI_OUTCOME_UNKNOWN":"ANALYSIS_FAILED",tasks.findById(task.getId()).orElseThrow().getErrorMessage());
            if(uncertain) assertEquals(409,retry(task,owner).statusCode());
            assertEquals(1,attempts.findByTaskIdOrderByCreatedAtAsc(task.getId()).size());
            assertEquals(1,CALLS.get()-before);
            evidence("AI_"+mode.toUpperCase(),Map.of("status","FAILED","attempts",1,"transportCalls",CALLS.get()-before));
            RELEASE.get().countDown(); Thread.sleep(40);
        }
    }

    @Test void authorizationRejectsBeforeAttemptCreationAndLegalStatesAreExplicit() throws Exception {
        AnalysisTask task=task(TaskStatus.FAILED);
        assertEquals(404,retry(task,other).statusCode()); assertEquals(401,retry(task,null).statusCode());
        assertEquals(0,attempts.findByTaskIdOrderByCreatedAtAsc(task.getId()).size());
        assertEquals(TaskStatus.FAILED,tasks.findById(task.getId()).orElseThrow().getStatus());
        assertEquals(200,retry(task,admin).statusCode()); completed(task,TaskStatus.SUCCEEDED);
        for(TaskStatus status:List.of(TaskStatus.PENDING,TaskStatus.RUNNING,TaskStatus.CANCEL_REQUESTED,TaskStatus.SUCCEEDED)) {
            AnalysisTask denied=task(status); assertEquals(409,retry(denied,owner).statusCode());
            assertEquals(0,attempts.findByTaskIdOrderByCreatedAtAsc(denied.getId()).size());
        }
        evidence("RETRY_AUTHORIZATION_AND_STATES",Map.of("other",404,"anonymous",401,"admin",200,"illegalStates",4));
    }

    @Test void retriesRetainReviewedEventsAndOldMediaBytesInDistinctAttemptDirectories() throws Exception {
        AnalysisTask task=task(TaskStatus.FAILED);
        DisputeEvent historical=event(task,null,ROOT.resolve("uploads/history.png"));
        ReviewRecord review=new ReviewRecord(); review.setEvent(historical); review.setReviewer(owner);
        review.setResult(ReviewResult.UNCERTAIN); review.setReasonCode("FIXTURE"); review.setRevision(1); reviews.save(review);
        byte[] bytes=Files.readAllBytes(Path.of(historical.getKeyframePath()));
        assertEquals(200,retry(task,owner).statusCode()); completed(task,TaskStatus.SUCCEEDED);
        String first=tasks.findById(task.getId()).orElseThrow().getCurrentAttemptId();
        DisputeEvent generated=events.findByTaskOrderByTimestampMsAsc(task).stream().filter(e->first.equals(e.getAttemptId())).findFirst().orElseThrow();
        byte[] generatedBytes=Files.readAllBytes(Path.of(generated.getKeyframePath()));
        AnalysisTask failed=tasks.findById(task.getId()).orElseThrow(); failed.setStatus(TaskStatus.FAILED); tasks.save(failed);
        assertEquals(200,retry(task,owner).statusCode()); completed(task,TaskStatus.SUCCEEDED);
        assertEquals(1,reviews.countByEvent(historical)); assertEquals(3,events.findByTaskOrderByTimestampMsAsc(task).size());
        assertArrayEquals(bytes,Files.readAllBytes(Path.of(historical.getKeyframePath())));
        assertArrayEquals(generatedBytes,Files.readAllBytes(Path.of(generated.getKeyframePath())));
        assertNotEquals(first,tasks.findById(task.getId()).orElseThrow().getCurrentAttemptId());
        evidence("HISTORY_PRESERVED",Map.of("events",3,"reviewCount",1,"attempts",2,"oldBytesUnchanged",true));
    }

    @Test void staleAuthenticatedCallbackAndOldCompletionCannotMutateCurrentAttempt() throws Exception {
        AnalysisTask task=task(TaskStatus.FAILED); hold(); assertEquals(200,retry(task,owner).statusCode());
        assertTrue(ENTERED.get().await(3,TimeUnit.SECONDS));
        String current=tasks.findById(task.getId()).orElseThrow().getCurrentAttemptId();
        String stale=callbacks.issue(task.getId(),UUID.randomUUID().toString());
        assertEquals(409,callback(task,stale));
        String valid=callbacks.issue(task.getId(),current); assertEquals(401,callback(task,stale)); assertEquals(200,callback(task,valid));
        assertEquals(44,tasks.findById(task.getId()).orElseThrow().getProgressPercent());
        // Simulate restart fencing while the old remote request is still in flight.
        service.quarantineInterruptedAttempts(); RELEASE.get().countDown();
        await(()->CALLS.get()==1); Thread.sleep(100);
        assertEquals(TaskStatus.FAILED,tasks.findById(task.getId()).orElseThrow().getStatus());
        assertEquals(0,events.findByTaskOrderByTimestampMsAsc(task).size());
        assertEquals(409,retry(task,owner).statusCode());
        evidence("STALE_CALLBACK_AND_COMPLETION",Map.of("staleAuthenticated",409,"oldToken",401,"currentToken",200,"lateEvents",0,"retryFenced",409));
    }

    @Test void restartQuarantinesPersistedQueuedAttemptWithoutReplayingIt() throws Exception {
        AnalysisTask task=task(TaskStatus.PENDING); TaskAttempt orphan=new TaskAttempt(); orphan.setTaskId(task.getId());
        orphan.setOwnerInstance(UUID.randomUUID().toString()); attempts.save(orphan); task.setCurrentAttemptId(orphan.getId()); tasks.save(task);
        service.quarantineInterruptedAttempts();
        AnalysisTask persisted=tasks.findById(task.getId()).orElseThrow(); assertEquals("RECOVERY_REQUIRED",persisted.getStage());
        assertEquals(TaskStatus.FAILED,persisted.getStatus()); assertEquals(1,attempts.countByTaskIdAndActiveTrue(task.getId()));
        assertEquals(409,retry(task,owner).statusCode()); assertEquals(0,CALLS.get());
        evidence("RESTART_FAIL_CLOSED",Map.of("stage",persisted.getStage(),"retry",409,"aiCalls",0,"quarantinedActive",1));
    }

    @Test void connectionRefusalAndUnexpectedWorkerExceptionBecomeQueryableFailures() throws Exception {
        int unused; try(var socket=new java.net.ServerSocket(0,0,InetAddress.getByName("127.0.0.1"))) { unused=socket.getLocalPort(); }
        AiClient refused=new AiClient("http://127.0.0.1:"+unused,UUID.randomUUID()+UUID.randomUUID().toString(),base(),
                new StorageService(ROOT.toString()),callbacks,200);
        doAnswer(call->refused.analyze(call.getArgument(0),call.getArgument(1))).when(aiClient).analyze(any(),anyString());
        AnalysisTask task=task(TaskStatus.FAILED); assertEquals(200,retry(task,owner).statusCode()); completed(task,TaskStatus.FAILED);
        assertEquals("AI_OUTCOME_UNKNOWN",tasks.findById(task.getId()).orElseThrow().getErrorMessage());
        assertEquals(409,retry(task,owner).statusCode());
        doThrow(new IllegalStateException("private-path-and-secret-must-not-escape")).when(aiClient).analyze(any(),anyString());
        AnalysisTask unexpected=task(TaskStatus.FAILED); var response=retry(unexpected,owner); assertEquals(200,response.statusCode()); completed(unexpected,TaskStatus.FAILED);
        assertEquals("ANALYSIS_FAILED",tasks.findById(unexpected.getId()).orElseThrow().getErrorMessage());
        evidence("REFUSAL_AND_UNEXPECTED_EXCEPTION",Map.of("refusedStatus","FAILED","unexpectedStatus","FAILED","sanitized",true));
    }

    @Test void h2MigrationPreservesLegacyRowsAndEnforcesAttemptPrimaryKey() throws Exception {
        String sql=Files.readString(Path.of(System.getProperty("wbs05.migration")));
        for(boolean legacy:List.of(false,true)) {
            try(var connection=java.sql.DriverManager.getConnection("jdbc:h2:mem:migration_"+UUID.randomUUID());var statement=connection.createStatement()) {
                statement.execute("CREATE TABLE analysis_task(id BIGINT PRIMARY KEY,status VARCHAR(24))");
                statement.execute("CREATE TABLE dispute_event(id BIGINT PRIMARY KEY,summary VARCHAR(80))");
                if(legacy) { statement.execute("INSERT INTO analysis_task VALUES(7,'FAILED')"); statement.execute("INSERT INTO dispute_event VALUES(9,'reviewed historical evidence')"); }
                for(String command:sql.replaceAll("(?m)--[^\\r\\n]*","").split(";")) if(!command.isBlank()) statement.execute(command);
                if(legacy) { try(var rows=statement.executeQuery("SELECT status,current_attempt_id FROM analysis_task WHERE id=7")) { assertTrue(rows.next()); assertEquals("FAILED",rows.getString(1)); assertNull(rows.getString(2)); } }
                String insert="INSERT INTO task_attempt(id,task_id,status,active,owner_instance,created_at) VALUES('fixture',7,'PENDING',true,'instance',CURRENT_TIMESTAMP)";
                statement.execute(insert); assertThrows(java.sql.SQLException.class,()->statement.execute(insert));
                try(var rows=statement.executeQuery("SELECT COUNT(*) FROM dispute_event")) { rows.next(); assertEquals(legacy?1:0,rows.getInt(1)); }
            }
        }
        evidence("H2_MIGRATION",Map.of("emptySchema",true,"legacyRowsPreserved",true,"duplicatePrimaryKeyRejected",true));
    }

    @Test void scheduledAttemptRunsThroughRealFastApiAndAuthenticatedProgress() throws Exception {
        int aiPort; try(var socket=new java.net.ServerSocket(0,0,InetAddress.getByName("127.0.0.1"))) { aiPort=socket.getLocalPort(); }
        String origin="http://127.0.0.1:"+aiPort,token=UUID.randomUUID()+UUID.randomUUID().toString();
        ProcessBuilder builder=new ProcessBuilder(Objects.requireNonNull(System.getProperty("wbs03.python")),"-B","-m","uvicorn","app.main:app","--host","127.0.0.1","--port",Integer.toString(aiPort),"--no-access-log","--log-level","critical");
        builder.directory(Path.of(System.getProperty("wbs03.aiDir")).toFile());
        builder.environment().putAll(Map.of("AI_SERVICE_TOKEN",token,"AI_STORAGE_ROOT",ROOT.toString(),"AI_CALLBACK_BASE_URL",base(),"AI_ANALYZER_MODE","mock","AI_TRACKER_MODE","mock","AI_YOLO_ALLOW_DOWNLOAD","false","PYTHONDONTWRITEBYTECODE","1"));
        builder.redirectErrorStream(true).redirectOutput(ROOT.resolve("attempt-fastapi.raw.log").toFile()); Process process=builder.start();
        try {
            await(()->{ try { return HTTP.send(HttpRequest.newBuilder(URI.create(origin+"/health")).timeout(Duration.ofMillis(300)).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode()==200; } catch(Exception e) { return false; } });
            AiClient bridge=new AiClient(origin,token,base(),new StorageService(ROOT.toString()),callbacks,5000);
            doAnswer(call->bridge.analyze(call.getArgument(0),call.getArgument(1))).when(aiClient).analyze(any(),anyString());
            AnalysisTask task=task(TaskStatus.FAILED); assertEquals(200,retry(task,owner).statusCode()); completed(task,TaskStatus.SUCCEEDED);
            AnalysisTask result=tasks.findById(task.getId()).orElseThrow();
            assertTrue(result.getOutputDir().contains(result.getCurrentAttemptId())); assertEquals(100,result.getProgressPercent());
            assertTrue(Files.isRegularFile(Path.of(result.getOutputDir()).resolve("analysis_result.json")));
            assertTrue(events.findByTaskOrderByTimestampMsAsc(task).stream().allMatch(e->result.getCurrentAttemptId().equals(e.getAttemptId())));
            evidence("SCHEDULED_REAL_FASTAPI_MOCK_CHAIN",Map.of("http",200,"status","SUCCEEDED","progress",100,"attemptDirectory",true,"events",events.findByTaskOrderByTimestampMsAsc(task).size(),"realModels",false));
        } finally { process.destroy(); if(!process.waitFor(5,TimeUnit.SECONDS)) process.destroyForcibly(); }
    }

    private int callback(AnalysisTask task,String token) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(base()+"/api/internal/tasks/"+task.getId()+"/progress"))
                .header("X-Internal-Token",token).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"stage\":\"DETECTING\",\"progressPercent\":44}")).build(),HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    @Test void attemptDirectoryRejectsJunctionBeforeCreatingAnythingOutside() throws Exception {
        Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        AnalysisTask task=task(TaskStatus.FAILED); Path parent=ROOT.resolve("tasks/task-"+task.getId());
        Path outside=Files.createTempDirectory("framecall-attempt-outside-"); Path link=parent.resolve("attempts");
        Process process=new ProcessBuilder("cmd","/c","mklink","/J",link.toString(),outside.toString()).redirectErrorStream(true).start();
        process.getInputStream().readAllBytes(); assertTrue(process.waitFor(3,TimeUnit.SECONDS)); assertEquals(0,process.exitValue());
        try {
            assertThrows(java.io.IOException.class,()->new StorageService(ROOT.toString()).attemptDir(task.getId(),UUID.randomUUID().toString()));
            try(var entries=Files.list(outside)) { assertEquals(0,entries.count()); }
            evidence("ATTEMPT_JUNCTION_NO_OUTSIDE_WRITE",Map.of("outsideEntries",0,"rejected",true));
        } finally { Files.delete(link); Files.delete(outside); }
    }
    private void hold() { MODE.set("hold"); RELEASE.set(new CountDownLatch(1)); ENTERED.set(new CountDownLatch(1)); }
    private String base() { return "http://127.0.0.1:"+port; }
    private HttpResponse<String> retry(AnalysisTask task,UserAccount actor) throws Exception { return send("/api/tasks/"+task.getId()+"/retry",actor,"application/json","{}"); }
    private HttpResponse<String> send(String path,UserAccount actor,String type,String body) throws Exception {
        var request=HttpRequest.newBuilder(URI.create(base()+path)).timeout(Duration.ofSeconds(10)).header("Content-Type",type);
        if(actor!=null) request.header("Authorization","Bearer "+jwt.issue(actor));
        return HTTP.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    private void completed(AnalysisTask task,TaskStatus state) throws Exception {
        await(()->{
            AnalysisTask current=tasks.findById(task.getId()).orElseThrow();
            return current.getStatus()==state && (attempts.countByTaskIdAndActiveTrue(task.getId())==0
                    || state==TaskStatus.FAILED && "AI_OUTCOME_UNKNOWN".equals(current.getErrorMessage()));
        });
    }
    private static void await(BooleanSupplier condition) throws Exception {
        long limit=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        while(!condition.getAsBoolean() && System.nanoTime()<limit) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(),"Bounded asynchronous condition not reached");
    }
    private UserAccount user(Role role) {
        UserAccount u=new UserAccount(); u.setUsername("schedule_"+UUID.randomUUID()); u.setDisplayName("synthetic");
        u.setRole(role); u.setEnabled(true); u.setPasswordHash("unusable"); return users.save(u);
    }
    private AnalysisTask task(TaskStatus status) throws Exception {
        Path path=ROOT.resolve("uploads/"+UUID.randomUUID()+".mp4"); Files.createDirectories(path.getParent()); Files.writeString(path,"synthetic");
        VideoAsset v=new VideoAsset(); v.setOwner(owner); v.setTitle("synthetic"); v.setCompetition("fixture"); v.setTeams("fixture"); v.setOriginalPath(path.toString()); v=videos.save(v);
        AnalysisTask t=new AnalysisTask(); t.setVideo(v); t.setStatus(status); return service.createTask(t);
    }
    private DisputeEvent event(AnalysisTask task,String attemptId,Path media) throws Exception {
        Files.writeString(media,"historic-bytes"); DisputeEvent e=new DisputeEvent(); e.setTask(task); e.setAttemptId(attemptId);
        e.setExternalId("historical"); e.setEventType(EventType.FOUL); e.setRiskLevel(RiskLevel.UNCERTAIN); e.setSummary("fixture");
        e.setKeyframePath(media.toString()); e.setClipPath(media.toString()); e.setInvolvedTrackIds("1"); return events.save(e);
    }
    private synchronized void evidence(String id,Map<String,Object> value) throws Exception {
        Map<String,Object> data=new LinkedHashMap<>(value); data.put("id",id); data.put("outcome","PASS"); data.put("at",Instant.now().toString());
        String output=System.getProperty("wbs05.evidence");
        if(output!=null) Files.writeString(Path.of(output),JSON.writeValueAsString(data)+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
    }
    private static Path temporary() { try { return Files.createTempDirectory("framecall-scheduling-"); } catch(Exception e) { throw new IllegalStateException(e); } }
    private static HttpServer fakeAi() {
        try {
            HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.setExecutor(Executors.newCachedThreadPool(r->{ Thread t=new Thread(r,"fake-ai"); t.setDaemon(true); return t; }));
            server.createContext("/internal/analyze",exchange->{
                try {
                    CALLS.incrementAndGet(); var payload=JSON.readValue(exchange.getRequestBody(),Map.class);
                    String mode=MODE.get(); CountDownLatch release=RELEASE.get(); ENTERED.get().countDown();
                    if(mode.equals("hold")) release.await(5,TimeUnit.SECONDS);
                    if(mode.equals("disconnect")) { exchange.close(); return; }
                    Path output=Path.of(payload.get("outputDir").toString()); Files.createDirectories(output); Path media=output.resolve("keyframe.png"); Files.writeString(media,"new-evidence-"+output.getFileName());
                    Map<String,Object> event=new LinkedHashMap<>(Map.of("externalId","SYNTHETIC","eventType","FOUL","riskLevel","UNCERTAIN","timestampMs",100,"windowStartMs",0,"windowEndMs",200,"summary","fixture","confidence",0.1,"keyframePath",media.toString(),"clipPath",media.toString()));
                    event.put("involvedTrackIds",List.of(1)); event.put("evidenceMetrics",List.of()); event.put("trackBoxes",List.of());
                    byte[] body=mode.equals("bad")?"{invalid".getBytes(StandardCharsets.UTF_8):JSON.writeValueAsBytes(Map.of("status","SUCCEEDED","resultJsonPath",output.resolve("result.json").toString(),"events",List.of(event)));
                    exchange.getResponseHeaders().set("Content-Type","application/json"); exchange.sendResponseHeaders(mode.equals("503")?503:200,body.length); exchange.getResponseBody().write(body);
                } catch(Exception ignored) { /* Client timeout intentionally closes the peer. */ }
                finally { exchange.close(); }
            }); server.start(); return server;
        } catch(Exception e) { throw new IllegalStateException(e); }
    }
}
