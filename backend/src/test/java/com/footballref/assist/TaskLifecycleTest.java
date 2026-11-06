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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.net.*;
import java.net.http.*;
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
class TaskLifecycleTest {
    private static final Path ROOT=temporary();
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final Map<Long,Scenario> SCENARIOS=new ConcurrentHashMap<>();
    private static final HttpServer AI=server();
    private static final HttpClient HTTP=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    @LocalServerPort int port;
    @SpyBean AnalysisTaskRepository tasks;
    @SpyBean AiClient client;
    @Autowired TaskAttemptRepository attempts;
    @Autowired VideoAssetRepository videos;
    @Autowired UserAccountRepository users;
    @Autowired DisputeEventRepository events;
    @Autowired ReviewRecordRepository reviews;
    @Autowired TaskService service;
    @Autowired StorageService storage;
    @Autowired AnalysisDispatcher dispatcher;
    @Autowired InternalCallbackService callbacks;
    @Autowired JwtService jwt;
    @jakarta.persistence.PersistenceContext jakarta.persistence.EntityManager entityManager;
    UserAccount owner,other,admin,assigned;
    final List<Scenario> own=new ArrayList<>();
    final AtomicInteger sequence=new AtomicInteger();

    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",()->"jdbc:h2:mem:lifecycle_"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        p.add("spring.datasource.driver-class-name",()->"org.h2.Driver");
        p.add("spring.jpa.hibernate.ddl-auto",()->"create-drop"); p.add("app.storage-root",ROOT::toString);
        p.add("app.ai-service-url",()->"http://127.0.0.1:"+AI.getAddress().getPort());
        p.add("app.analysis.workers",()->1);p.add("app.analysis.queue-capacity",()->1);
        p.add("app.analysis.read-timeout-ms",()->10000);p.add("app.analysis.cancel-timeout-ms",()->400);
    }
    @BeforeEach void accountsAndIoChecks() {
        owner=user(Role.REFEREE);other=user(Role.REFEREE);admin=user(Role.ADMIN);assigned=user(Role.REFEREE);
        doAnswer(call->{
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),"Analysis I/O held a DB transaction");
            AnalysisTask task=call.getArgument(0);SCENARIOS.get(task.getId()).worker=Thread.currentThread().getName();
            return call.callRealMethod();
        }).when(client).analyze(any(),anyString());
        doAnswer(call->{
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),"Cancel I/O held a DB transaction");
            return call.callRealMethod();
        }).when(client).cancel(anyLong());
    }
    @AfterEach void drainOnlySyntheticWork() throws Exception {
        for(Scenario s:own) { s.analyzeRelease.countDown();s.cancelRelease.countDown(); }
        await(()->dispatcher.queued()==0 && own.stream().allMatch(s->{
            AnalysisTask task=current(s);return task.getStatus()!=TaskStatus.RUNNING && task.getStatus()!=TaskStatus.PENDING;
        }));
    }
    @AfterAll static void stopServer() { AI.stop(0); }

    @Test void cancellationCommitsBeforeBothIoResponsesAndDiscardsLateSuccessAndEvents() throws Exception {
        Scenario s=task(); DisputeEvent old=historical(s);
        ReviewRecord review=new ReviewRecord();review.setEvent(old);review.setReviewer(owner);review.setResult(ReviewResult.UNCERTAIN);
        review.setReasonCode("FIXTURE");review.setRevision(1);reviews.save(review);
        byte[] media=Files.readAllBytes(Path.of(old.getKeyframePath()));
        s.cancelRelease=new CountDownLatch(1);start(s);
        assertEquals(200,progress(s,s.token,"DETECTING",45));
        ExecutorService requests=Executors.newSingleThreadExecutor();
        try {
            Future<HttpResponse<String>> pending=requests.submit(()->cancel(s,owner));
            assertTrue(s.cancelEntered.await(3,TimeUnit.SECONDS));
            assertEquals(TaskStatus.CANCEL_REQUESTED,current(s).getStatus());assertEquals(45,current(s).getProgressPercent());
            assertFalse(pending.isDone());assertEquals(1,attempts.countByTaskIdAndActiveTrue(s.id));
            assertEquals(200,get(s).statusCode());record(s,"CANCEL_BARRIER_COMMITTED_BEFORE_REMOTE_RESPONSE",null);
            assertEquals(409,progress(s,s.token,"COMPLETED",100));
            s.analyzeRelease.countDown();await(()->attempt(s).isExecutionFinished());
            assertEquals(TaskStatus.CANCEL_REQUESTED,current(s).getStatus());assertEquals("SENDING",attempt(s).getCancelOutcome());
            assertNull(current(s).getResultJsonPath());assertEquals(1,eventCount(s));assertEquals(409,retry(s,owner).statusCode());
            record(s,"LATE_SUCCESS_DISCARDED_CANCEL_HTTP_STILL_PENDING",409);
            s.cancelRelease.countDown();assertEquals(200,pending.get(5,TimeUnit.SECONDS).statusCode());
            assertEquals(TaskStatus.CANCELLED,current(s).getStatus());assertFalse(attempt(s).isActive());
            assertEquals(200,cancel(s,owner).statusCode());assertEquals(1,s.cancelCalls.get());
            assertArrayEquals(media,Files.readAllBytes(Path.of(old.getKeyframePath())));assertEquals(1,reviews.countByEvent(old));
            record(s,"CANCELLED_AFTER_BOTH_RESPONSES_HISTORY_UNCHANGED",200);
            assertEquals(200,retry(s,owner).statusCode());await(()->current(s).getStatus()==TaskStatus.SUCCEEDED);
            assertEquals(2,s.analyzeCalls.get());assertEquals(1,reviews.countByEvent(old));
        } finally { s.cancelRelease.countDown();requests.shutdownNow(); }
    }

    @Test void successCommittedBeforeCancelIsConflictAndTerminalCallbackCannotReopenIt() throws Exception {
        Scenario s=task();start(s);s.analyzeRelease.countDown();await(()->current(s).getStatus()==TaskStatus.SUCCEEDED);
        int before=eventCount(s);assertEquals(409,cancel(s,owner).statusCode());assertEquals(0,s.cancelCalls.get());
        String valid=callbacks.issue(s.id,current(s).getCurrentAttemptId());assertEquals(409,progress(s,valid,"DETECTING",30));
        assertEquals(100,current(s).getProgressPercent());assertEquals(before,eventCount(s));
        record(s,"COMPLETION_COMMITTED_FIRST_CANCEL_409",409);
    }

    @Test void progressIsVisibleMonotonicIdempotentAndCannotDeclareSuccess() throws Exception {
        Scenario s=task();start(s);
        assertEquals(200,progress(s,s.token,"DETECTING",40));assertEquals(200,get(s).statusCode());
        assertEquals(409,progress(s,s.token,"DETECTING",30));assertEquals(409,progress(s,s.token,"EXTRACTING",60));
        assertEquals(200,progress(s,s.token,"TRACKING",70));Instant updated=current(s).getUpdatedAt();
        assertEquals(200,progress(s,s.token,"TRACKING",70));assertEquals(updated,current(s).getUpdatedAt());
        assertEquals(400,progress(s,s.token,"INVALID",80));assertEquals(400,progress(s,s.token,"TRACKING",101));
        String stale=callbacks.issue(s.id,UUID.randomUUID().toString());assertEquals(409,progress(s,stale,"COMPLETED",100));
        String valid=callbacks.issue(s.id,current(s).getCurrentAttemptId());assertEquals(401,progress(s,stale,"TRACKING",80));
        assertEquals(200,progress(s,valid,"EVENT_ANALYSIS",80));assertEquals(200,progress(s,valid,"COMPLETED",100));
        assertEquals(99,current(s).getProgressPercent());assertEquals(TaskStatus.RUNNING,current(s).getStatus());assertEquals(0,eventCount(s));
        record(s,"MONOTONIC_PROGRESS_VISIBLE_SUCCESS_NOT_COMMITTED",200);
        s.analyzeRelease.countDown();await(()->current(s).getStatus()==TaskStatus.SUCCEEDED);assertEquals(100,current(s).getProgressPercent());
        assertEquals(409,progress(s,valid,"COMPLETED",100));
    }

    @Test void twentyConcurrentMixedCancelAndRetryRequestsKeepOneExecution() throws Exception {
        Scenario s=task();start(s);ExecutorService requests=Executors.newFixedThreadPool(20);
        CountDownLatch ready=new CountDownLatch(20),go=new CountDownLatch(1);List<Future<Integer>> results=new ArrayList<>();
        try {
            for(int i=0;i<20;i++) { boolean cancelling=i%2==0;results.add(requests.submit(()->{ready.countDown();go.await();return (cancelling?cancel(s,owner):retry(s,owner)).statusCode();})); }
            assertTrue(ready.await(5,TimeUnit.SECONDS));go.countDown();List<Integer> codes=new ArrayList<>();
            for(Future<Integer> f:results)codes.add(f.get(8,TimeUnit.SECONDS));
            assertEquals(10,Collections.frequency(codes,202));assertEquals(10,Collections.frequency(codes,409));
            assertEquals(1,s.cancelCalls.get());assertEquals(1,s.analyzeCalls.get());assertEquals(1,attempts.countByTaskIdAndActiveTrue(s.id));
            record(s,"20_MIXED_10_ACCEPTED_CANCEL_10_RETRY_CONFLICT",202);
            s.analyzeRelease.countDown();await(()->current(s).getStatus()==TaskStatus.CANCELLED);assertEquals(0,eventCount(s));
        } finally {go.countDown();requests.shutdownNow();}
    }

    @Test void cancelAckRejectionDisconnectAndTimeoutHaveExplicitSafeOutcomes() throws Exception {
        for(String mode:List.of("accepted","503","disconnect","timeout")) {
            Scenario s=task();s.mode=mode;if(mode.equals("timeout"))s.cancelRelease=new CountDownLatch(1);start(s);
            var response=cancel(s,owner);assertEquals(202,response.statusCode());assertEquals(409,retry(s,owner).statusCode());
            boolean uncertain=mode.equals("disconnect")||mode.equals("timeout");
            assertEquals(uncertain?"UNKNOWN":mode.equals("503")?"REJECTED":"ACCEPTED",attempt(s).getCancelOutcome());
            assertEquals(1,attempts.countByTaskIdAndActiveTrue(s.id));record(s,"CANCEL_REMOTE_"+mode.toUpperCase(),response.statusCode());
            s.analyzeRelease.countDown();
            if(uncertain) {
                assertTrue(s.analyzeExited.await(4,TimeUnit.SECONDS));
                assertEquals(TaskStatus.CANCEL_REQUESTED,current(s).getStatus());assertEquals("CANCEL_OUTCOME_UNKNOWN",current(s).getErrorMessage());
                assertEquals(409,retry(s,owner).statusCode());assertTrue(attempt(s).isActive());
            } else {
                await(()->current(s).getStatus()==TaskStatus.CANCELLED);assertFalse(attempt(s).isActive());
                if(mode.equals("503"))assertEquals("CANCEL_REMOTE_REJECTED",current(s).getErrorMessage());
            }
            assertEquals(0,eventCount(s));s.cancelRelease.countDown();record(s,"ANALYZE_EXIT_AFTER_CANCEL_"+mode.toUpperCase(),null);
        }
    }

    @Test void analyzeTimeoutAfterAcceptedCancelNeverReleasesUnknownWork() throws Exception {
        AiClient shortRead=new AiClient(origin(),UUID.randomUUID()+UUID.randomUUID().toString(),base(),storage,callbacks,350,400);
        doAnswer(call->shortRead.analyze(call.getArgument(0),call.getArgument(1))).when(client).analyze(any(),anyString());
        Scenario s=task();start(s);assertEquals(202,cancel(s,owner).statusCode());
        await(()->"AI_OUTCOME_UNKNOWN".equals(current(s).getErrorMessage()));
        assertEquals(TaskStatus.CANCEL_REQUESTED,current(s).getStatus());assertTrue(attempt(s).isActive());
        assertEquals(409,retry(s,owner).statusCode());assertEquals(0,eventCount(s));record(s,"CANCEL_ACCEPTED_ANALYZE_OUTCOME_UNKNOWN",409);
    }

    @Test void queuedCancellationNeverContactsAiAndSkippedRunnableCannotReviveIt() throws Exception {
        Scenario running=task();start(running);Scenario queued=task();service.runTask(queued.id);assertEquals(1,dispatcher.queued());
        assertEquals(200,cancel(queued,owner).statusCode());assertEquals(TaskStatus.CANCELLED,current(queued).getStatus());
        assertFalse(attempt(queued).isActive());running.analyzeRelease.countDown();await(()->dispatcher.queued()==0);
        assertEquals(0,queued.analyzeCalls.get());assertEquals(0,queued.cancelCalls.get());assertEquals(0,eventCount(queued));
        record(queued,"QUEUED_CANCEL_NO_NETWORK_NO_REVIVAL",200);
    }

    @Test void authorizationRunsBeforeCancelOrRetrySideEffectsAndSharedAccessRemains() throws Exception {
        Scenario s=task();VideoAsset video=videos.findById(current(s).getVideo().getId()).orElseThrow();video.setAssignedTo(assigned);videos.save(video);start(s);
        assertEquals(404,cancel(s,other).statusCode());assertEquals(401,cancel(s,null).statusCode());
        assertEquals(404,retry(s,other).statusCode());assertEquals(401,retry(s,null).statusCode());
        assertEquals(TaskStatus.RUNNING,current(s).getStatus());assertEquals(0,s.cancelCalls.get());
        assertEquals(202,cancel(s,assigned).statusCode());assertEquals(202,cancel(s,admin).statusCode());assertEquals(1,s.cancelCalls.get());
        s.analyzeRelease.countDown();await(()->current(s).getStatus()==TaskStatus.CANCELLED);record(s,"AUTHORIZATION_ZERO_DENIED_SIDE_EFFECTS",202);
    }

    @Test void cancelledBarrierTransactionRollbackDoesNotPretendAcceptanceOrCallRemote() throws Exception {
        Scenario s=task();start(s);AtomicBoolean fail=new AtomicBoolean(true);
        doAnswer(call->{AnalysisTask t=call.getArgument(0);if(t.getId().equals(s.id)&&t.getStatus()==TaskStatus.CANCEL_REQUESTED&&fail.getAndSet(false))throw new DataIntegrityViolationException("synthetic transaction failure");return persistTask(t);}).when(tasks).save(any());
        assertEquals(500,cancel(s,owner).statusCode());assertEquals(0,s.cancelCalls.get());assertEquals(TaskStatus.RUNNING,current(s).getStatus());
        record(s,"CANCEL_TRANSACTION_ROLLBACK_NO_ACCEPTANCE",500);s.analyzeRelease.countDown();await(()->current(s).getStatus()==TaskStatus.SUCCEEDED);
    }

    @Test void successTransactionFailureRollsBackEventsBeforeQueryableFailureAndLegalRetry() throws Exception {
        Scenario s=task();start(s);AtomicBoolean fail=new AtomicBoolean(true);
        doAnswer(call->{AnalysisTask t=call.getArgument(0);if(t.getId().equals(s.id)&&t.getStatus()==TaskStatus.SUCCEEDED&&fail.getAndSet(false))throw new DataIntegrityViolationException("synthetic transaction failure");return persistTask(t);}).when(tasks).save(any());
        s.analyzeRelease.countDown();await(()->current(s).getStatus()==TaskStatus.FAILED);assertEquals(0,eventCount(s));assertFalse(attempt(s).isActive());
        String token=callbacks.issue(s.id,current(s).getCurrentAttemptId());assertEquals(409,progress(s,token,"COMPLETED",100));
        record(s,"RESULT_TRANSACTION_ROLLBACK_ZERO_EVENTS",null);
        assertEquals(200,retry(s,owner).statusCode());await(()->current(s).getStatus()==TaskStatus.SUCCEEDED);assertEquals(1,eventCount(s));
    }

    @Test void cancelOutcomeTransactionFailureKeepsBarrierAndRestartQuarantinesIt() throws Exception {
        Scenario s=task();start(s);AtomicInteger writes=new AtomicInteger();
        doAnswer(call->{AnalysisTask t=call.getArgument(0);if(t.getId().equals(s.id)&&t.getStatus()==TaskStatus.CANCEL_REQUESTED&&writes.incrementAndGet()==2)throw new DataIntegrityViolationException("synthetic outcome persistence failure");return persistTask(t);}).when(tasks).save(any());
        assertEquals(500,cancel(s,owner).statusCode());assertEquals(TaskStatus.CANCEL_REQUESTED,current(s).getStatus());
        assertEquals("SENDING",attempt(s).getCancelOutcome());assertEquals(1,s.cancelCalls.get());assertEquals(409,retry(s,owner).statusCode());
        s.analyzeRelease.countDown();await(()->attempt(s).isExecutionFinished());assertEquals(0,eventCount(s));
        record(s,"OUTCOME_TRANSACTION_ROLLBACK_BARRIER_RETAINED",500);
        service.quarantineInterruptedAttempts();assertEquals("RECOVERY_REQUIRED",current(s).getStage());assertTrue(attempt(s).isActive());
        assertEquals(409,retry(s,owner).statusCode());record(s,"RESTART_QUARANTINES_CANCEL_SENDING",409);
    }

    @Test void additiveMigrationPreservesLegacyAttemptsAndDefaultsToNoStopClaim() throws Exception {
        String baseline=Files.readString(Path.of(System.getProperty("wbs05.migration")));
        String upgrade=Files.readString(Path.of(System.getProperty("wbs06.migration")));
        for(boolean legacy:List.of(false,true))try(var connection=java.sql.DriverManager.getConnection("jdbc:h2:mem:upgrade_"+UUID.randomUUID());var statement=connection.createStatement()) {
            statement.execute("CREATE TABLE analysis_task(id BIGINT PRIMARY KEY,status VARCHAR(24))");statement.execute("CREATE TABLE dispute_event(id BIGINT PRIMARY KEY)");
            for(String sql:(baseline+"\n").replaceAll("(?m)--[^\\r\\n]*","").split(";"))if(!sql.isBlank())statement.execute(sql);
            if(legacy)statement.execute("INSERT INTO task_attempt(id,task_id,status,active,owner_instance,created_at) VALUES('old',7,'RUNNING',true,'old-owner',CURRENT_TIMESTAMP)");
            for(String sql:upgrade.replaceAll("(?m)--[^\\r\\n]*","").split(";"))if(!sql.isBlank())statement.execute(sql);
            if(legacy)try(var row=statement.executeQuery("SELECT active,execution_finished,cancel_outcome FROM task_attempt WHERE id='old'")) {assertTrue(row.next());assertTrue(row.getBoolean(1));assertFalse(row.getBoolean(2));assertNull(row.getString(3));}
        }
        Map<String,Object> data=new LinkedHashMap<>();data.put("id","H2_ADDITIVE_MIGRATION");data.put("legacyActivePreserved",true);data.put("executionFinishedDefault",false);write(data);
    }

    private Scenario task() throws Exception {
        Path input=ROOT.resolve("uploads/"+UUID.randomUUID()+".mp4");Files.createDirectories(input.getParent());Files.writeString(input,"synthetic");
        VideoAsset video=new VideoAsset();video.setOwner(owner);video.setTitle("synthetic");video.setCompetition("fixture");video.setTeams("fixture");video.setOriginalPath(input.toString());video=videos.save(video);
        AnalysisTask task=new AnalysisTask();task.setVideo(video);task=service.createTask(task);
        Scenario s=new Scenario(task.getId());SCENARIOS.put(s.id,s);own.add(s);return s;
    }
    private AnalysisTask persistTask(AnalysisTask task) {
        // JDK repository proxies have no concrete callRealMethod; retain real JPA persistence in the fault wrapper.
        return new org.springframework.data.jpa.repository.support.SimpleJpaRepository<AnalysisTask,Long>(AnalysisTask.class,entityManager).save(task);
    }
    private void start(Scenario s) throws Exception {service.runTask(s.id);assertTrue(s.analyzeEntered.await(4,TimeUnit.SECONDS));}
    private AnalysisTask current(Scenario s) {return tasks.findById(s.id).orElseThrow();}
    private TaskAttempt attempt(Scenario s) {return attempts.findById(current(s).getCurrentAttemptId()).orElseThrow();}
    private int eventCount(Scenario s) {return events.findByTaskOrderByTimestampMsAsc(current(s)).size();}
    private String base() {return "http://127.0.0.1:"+port;}
    private static String origin() {return "http://127.0.0.1:"+AI.getAddress().getPort();}
    private HttpResponse<String> cancel(Scenario s,UserAccount user) throws Exception {return post("/api/tasks/"+s.id+"/cancel",user);}
    private HttpResponse<String> retry(Scenario s,UserAccount user) throws Exception {return post("/api/tasks/"+s.id+"/retry",user);}
    private HttpResponse<String> post(String path,UserAccount user) throws Exception {
        var request=HttpRequest.newBuilder(URI.create(base()+path)).timeout(Duration.ofSeconds(10)).header("Content-Type","application/json");
        if(user!=null)request.header("Authorization","Bearer "+jwt.issue(user));
        return HTTP.send(request.POST(HttpRequest.BodyPublishers.ofString("{}")).build(),HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> get(Scenario s) throws Exception {return HTTP.send(HttpRequest.newBuilder(URI.create(base()+"/api/tasks/"+s.id)).header("Authorization","Bearer "+jwt.issue(owner)).timeout(Duration.ofSeconds(2)).GET().build(),HttpResponse.BodyHandlers.ofString());}
    private int progress(Scenario s,String token,String stage,int value) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(base()+"/api/internal/tasks/"+s.id+"/progress")).header("X-Internal-Token",token).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of("stage",stage,"progressPercent",value)))).build(),HttpResponse.BodyHandlers.discarding()).statusCode();
    }
    private DisputeEvent historical(Scenario s) throws Exception {
        Path image=ROOT.resolve("uploads/history_"+UUID.randomUUID()+".png");Files.writeString(image,"old-evidence-bytes");
        DisputeEvent e=new DisputeEvent();e.setTask(current(s));e.setExternalId("HISTORY");e.setEventType(EventType.FOUL);e.setRiskLevel(RiskLevel.UNCERTAIN);
        e.setSummary("fixture");e.setKeyframePath(image.toString());e.setClipPath(image.toString());e.setInvolvedTrackIds("1");return events.save(e);
    }
    private UserAccount user(Role role) {UserAccount u=new UserAccount();u.setUsername("lifecycle_"+UUID.randomUUID());u.setDisplayName("synthetic");u.setRole(role);u.setEnabled(true);u.setPasswordHash("unusable");return users.save(u);}
    private void record(Scenario s,String label,Integer http) throws Exception {
        AnalysisTask t=current(s);TaskAttempt a=attempt(s);Map<String,Object> data=new LinkedHashMap<>();
        data.put("id",label);data.put("order",sequence.incrementAndGet());data.put("taskId",s.id);data.put("attemptId",a.getId());data.put("http",http);
        data.put("taskStatus",t.getStatus().name());data.put("attemptStatus",a.getStatus().name());data.put("active",a.isActive());data.put("executionFinished",a.isExecutionFinished());data.put("cancelOutcome",a.getCancelOutcome());
        data.put("stage",t.getStage());data.put("progress",t.getProgressPercent());data.put("errorCode",t.getErrorMessage());data.put("eventCount",eventCount(s));data.put("resultPathPresent",t.getResultJsonPath()!=null);
        data.put("analyzeCalls",s.analyzeCalls.get());data.put("cancelCalls",s.cancelCalls.get());data.put("remoteCancelHttp",s.remoteCancelHttp);data.put("workerThread",s.worker);data.put("observerThread",Thread.currentThread().getName());write(data);
    }
    private synchronized void write(Map<String,Object> data) throws Exception {
        data.put("at",Instant.now().toString());data.put("outcome","PASS");String file=System.getProperty("wbs06.evidence");
        if(file!=null)Files.writeString(Path.of(file),JSON.writeValueAsString(data)+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
    }
    private static void await(BooleanSupplier predicate) throws Exception {long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);while(!predicate.getAsBoolean()&&System.nanoTime()<until)Thread.sleep(10);assertTrue(predicate.getAsBoolean(),"Bounded lifecycle condition not reached");}
    private static Path temporary() {try{return Files.createTempDirectory("framecall-lifecycle-");}catch(Exception e){throw new IllegalStateException(e);}}
    private static class Scenario {
        final Long id;final CountDownLatch analyzeEntered=new CountDownLatch(1),analyzeRelease=new CountDownLatch(1),analyzeExited=new CountDownLatch(1),cancelEntered=new CountDownLatch(1);
        volatile CountDownLatch cancelRelease=new CountDownLatch(0);volatile String mode="accepted",token,worker;volatile int remoteCancelHttp;
        final AtomicInteger analyzeCalls=new AtomicInteger(),cancelCalls=new AtomicInteger();Scenario(Long id){this.id=id;}
    }
    private static HttpServer server() {
        try {
            HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.setExecutor(Executors.newCachedThreadPool(r->{Thread t=new Thread(r,"lifecycle-fake-ai");t.setDaemon(true);return t;}));
            server.createContext("/internal/analyze",exchange->{Scenario s=null;try {
                var payload=JSON.readValue(exchange.getRequestBody(),Map.class);s=SCENARIOS.get(((Number)payload.get("taskId")).longValue());
                s.analyzeCalls.incrementAndGet();s.token=exchange.getRequestHeaders().getFirst("X-Callback-Token");s.analyzeEntered.countDown();s.analyzeRelease.await(12,TimeUnit.SECONDS);
                Path output=Path.of(payload.get("outputDir").toString());Files.createDirectories(output);Path media=output.resolve("keyframe.png");Files.writeString(media,"new-synthetic-bytes");
                Map<String,Object> event=new LinkedHashMap<>(Map.of("externalId","NEW","eventType","FOUL","riskLevel","UNCERTAIN","timestampMs",100,"windowStartMs",0,"windowEndMs",200,"summary","synthetic","confidence",0.1,"keyframePath",media.toString(),"clipPath",media.toString()));
                event.put("involvedTrackIds",List.of(1));event.put("evidenceMetrics",List.of());event.put("trackBoxes",List.of());
                byte[] bytes=JSON.writeValueAsBytes(Map.of("status","SUCCEEDED","events",List.of(event),"resultJsonPath",output.resolve("result.json").toString()));
                exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
            } catch(Exception expectedForTimedOutPeer) { /* Transport-fault fixtures close the client deliberately. */ }
            finally {exchange.close();if(s!=null)s.analyzeExited.countDown();}});
            server.createContext("/internal/tasks/",exchange->{try {
                Long id=Long.valueOf(exchange.getRequestURI().getPath().split("/")[3]);Scenario s=SCENARIOS.get(id);s.cancelCalls.incrementAndGet();s.cancelEntered.countDown();s.cancelRelease.await(12,TimeUnit.SECONDS);
                if(s.mode.equals("disconnect"))return;
                s.remoteCancelHttp=s.mode.equals("503")?503:200;exchange.sendResponseHeaders(s.remoteCancelHttp,-1);
            }catch(Exception expectedForTimedOutPeer) { /* No response after the transport deadline is expected. */ }
            finally {exchange.close();}});server.start();return server;
        }catch(Exception e){throw new IllegalStateException(e);}
    }
}
