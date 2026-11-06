package com.footballref.assist;

import com.fasterxml.jackson.databind.*;
import com.footballref.assist.domain.*;
import com.footballref.assist.domain.enums.*;
import com.footballref.assist.repository.*;
import com.footballref.assist.service.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.dao.DataIntegrityViolationException;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import javax.imageio.ImageIO;
import javax.sql.DataSource;
import java.awt.image.BufferedImage;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class TaskHistoryTest {
    private static final Path ROOT=temporary();
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final AtomicInteger AI_CALLS=new AtomicInteger(),CANCEL_CALLS=new AtomicInteger();
    private static volatile CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(0);
    private static volatile boolean reject;
    private static volatile String grant;
    private static final HttpServer AI=server();
    @Autowired MockMvc mvc;
    @SpyBean AnalysisTaskRepository tasks;
    @Autowired TaskAttemptRepository attempts;
    @Autowired DisputeEventRepository events;
    @Autowired ReviewRecordRepository reviews;
    @Autowired UserAccountRepository users;
    @Autowired VideoAssetRepository videos;
    @Autowired TaskService service;
    @Autowired JwtService jwt;
    @Autowired DataSource dataSource;
    @org.springframework.boot.test.web.server.LocalServerPort int port;
    @jakarta.persistence.PersistenceContext jakarta.persistence.EntityManager entityManager;
    UserAccount owner,other,admin,assigned;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",()->"jdbc:h2:mem:history_"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        p.add("spring.jpa.hibernate.ddl-auto",()->"create-drop");p.add("spring.datasource.driver-class-name",()->"org.h2.Driver");
        p.add("app.storage-root",ROOT::toString);p.add("app.ai-service-url",()->"http://127.0.0.1:"+AI.getAddress().getPort());
        p.add("app.analysis.workers",()->1);p.add("app.analysis.queue-capacity",()->2);
    }
    @BeforeEach void setup() {owner=user(Role.REFEREE);other=user(Role.REFEREE);admin=user(Role.ADMIN);assigned=user(Role.REFEREE);AI_CALLS.set(0);CANCEL_CALLS.set(0);entered=new CountDownLatch(1);release=new CountDownLatch(0);reject=false;}
    @AfterEach void unblock() {release.countDown();}
    @AfterAll static void stop() {AI.stop(0);}

    @Test void archivalWithAndWithoutReviewsPreservesRowsAndBytesAndRestoresAccess() throws Exception {
        for(boolean reviewed:List.of(false,true)) {
            AnalysisTask task=task();DisputeEvent event=reviewed?event(task,null,"LEGACY_TAG"):null;
            if(reviewed)review(event,owner,200);
            long eventCount=events.count(),reviewCount=reviews.count();String hash=event==null?"none":hash(Path.of(event.getKeyframePath()));
            call(delete(path(task)),owner,200);assertNotNull(current(task).getArchivedAt());
            assertEquals(eventCount,events.count());assertEquals(reviewCount,reviews.count());
            call(get(path(task)),owner,404);call(get(path(task)+"/report"),owner,404);call(post(path(task)+"/retry"),owner,404);
            if(event!=null) {call(get("/api/files?eventId="+event.getId()+"&kind=keyframe"),owner,404);review(event,owner,404);assertEquals(hash,hash(Path.of(event.getKeyframePath())));}
            call(post(path(task)+"/restore"),other,404);call(post(path(task)+"/restore"),owner,200);
            assertNull(current(task).getArchivedAt());call(get(path(task)),owner,200);
            record("ARCHIVE_RESTORE_"+reviewed,Map.of("taskId",task.getId(),"eventsBefore",eventCount,"eventsAfter",events.count(),"reviewsBefore",reviewCount,"reviewsAfter",reviews.count(),"mediaHash",hash));
        }
    }

    @Test void retriesAndPdfSelectCurrentAttemptWithoutMixingLegacyOrPriorSuccess() throws Exception {
        AnalysisTask task=task();DisputeEvent old=event(task,null,"LEGACY_TAG");review(old,owner,200);String oldHash=hash(Path.of(old.getKeyframePath()));
        call(post(path(task)+"/retry"),owner,200);await(()->current(task).getStatus()==TaskStatus.SUCCEEDED);
        String first=current(task).getCurrentAttemptId();DisputeEvent generated=events.findByTaskOrderByTimestampMsAsc(task).stream().filter(e->first.equals(e.getAttemptId())).findFirst().orElseThrow();
        review(generated,owner,200);String firstHash=hash(Path.of(generated.getKeyframePath()));
        assertEquals(1,body(call(get(path(task)),owner,200)).path("events").size());
        AnalysisTask failed=current(task);failed.setStatus(TaskStatus.FAILED);tasks.save(failed);reject=true;
        call(post(path(task)+"/retry"),owner,200);await(()->current(task).getStatus()==TaskStatus.FAILED && !attempts.existsByTaskIdAndActiveTrue(task.getId()));
        assertEquals(0,body(call(get(path(task)),owner,200)).path("events").size());
        assertEquals(2,body(call(get(path(task)+"?scope=all"),owner,200)).path("events").size());
        assertEquals(1,body(call(get(path(task)+"?scope=legacy"),owner,200)).path("events").size());
        assertEquals(1,body(call(get(path(task)+"?attemptId="+first),owner,200)).path("events").size());
        String currentPdf=pdf(call(get(path(task)+"/report"),owner,200).getResponse().getContentAsByteArray());
        String oldPdf=pdf(call(get(path(task)+"/report?attemptId="+first),owner,200).getResponse().getContentAsByteArray());
        String allPdf=pdf(call(get(path(task)+"/report?scope=all"),owner,200).getResponse().getContentAsByteArray());
        assertFalse(currentPdf.contains("LEGACY_TAG"));assertFalse(currentPdf.contains("AI_NEW_TAG"));assertTrue(oldPdf.contains("AI_NEW_TAG"));assertFalse(oldPdf.contains("LEGACY_TAG"));assertTrue(allPdf.contains("LEGACY_TAG"));assertTrue(allPdf.contains("AI_NEW_TAG"));
        assertEquals(oldHash,hash(Path.of(old.getKeyframePath())));assertEquals(firstHash,hash(Path.of(generated.getKeyframePath())));assertEquals(1,reviews.countByEvent(old));assertEquals(1,reviews.countByEvent(generated));
        record("ATTEMPT_FILTER_AND_PDF",Map.of("taskId",task.getId(),"currentEvents",0,"allEvents",2,"legacyEvents",1,"priorAttemptEvents",1,"oldMediaHash",oldHash,"priorMediaHash",firstHash));
    }

    @Test void ownerAdministratorAndAssignedCanReadHistoryButCrossUserOperationsHaveNoSideEffects() throws Exception {
        AnalysisTask task=task();VideoAsset video=task.getVideo();video.setAssignedTo(assigned);videos.save(video);DisputeEvent old=event(task,null,"ACCESS");review(old,owner,200);
        long count=reviews.count();String hash=hash(Path.of(old.getKeyframePath()));
        for(UserAccount actor:List.of(owner,admin,assigned)) {call(get(path(task)+"?scope=all"),actor,200);call(get(path(task)+"/report?scope=all"),actor,200);}
        for(var request:List.of(get(path(task)+"?scope=all"),get(path(task)+"/report"),delete(path(task)),post(path(task)+"/restore")))call(request,other,404);
        call(get(path(task)+"?scope=all"),null,401);call(delete(path(task)),null,401);call(get("/api/files?eventId="+old.getId()+"&kind=keyframe"),null,401);
        assertEquals(0,body(call(get("/api/tasks?includeArchived=true"),other,200)).size());assertNull(current(task).getArchivedAt());assertEquals(count,reviews.count());assertEquals(hash,hash(Path.of(old.getKeyframePath())));
        call(delete(path(task)),admin,200);call(post(path(task)+"/restore"),assigned,200);
        record("HISTORY_AUTHORIZATION",Map.of("taskId",task.getId(),"deniedReviewsUnchanged",true,"deniedMediaHashUnchanged",true,"adminArchive",200,"assignedRestore",200));
    }

    @Test void twentyMixedArchiveRetryCancelReviewAndCallbackRequestsKeepAuditAndOneAttempt() throws Exception {
        AnalysisTask task=task();DisputeEvent old=event(task,null,"RACE");review(old,owner,200);String hash=hash(Path.of(old.getKeyframePath()));
        release=new CountDownLatch(1);call(post(path(task)+"/retry"),owner,200);assertTrue(entered.await(4,TimeUnit.SECONDS));
        ExecutorService pool=Executors.newFixedThreadPool(20);CountDownLatch ready=new CountDownLatch(20),go=new CountDownLatch(1);List<Future<Integer>> futures=new ArrayList<>();
        try {
            for(int i=0;i<20;i++) {int operation=i%5;futures.add(pool.submit(()->{ready.countDown();go.await();return switch(operation) {
                case 0->status(delete(path(task)),owner);case 1->status(post(path(task)+"/retry"),owner);case 2->status(post(path(task)+"/cancel"),owner);
                case 3->status(reviewRequest(old),owner);default->status(post("/api/internal/tasks/"+task.getId()+"/progress").header("X-Internal-Token",grant).contentType("application/json").content("{\"stage\":\"DETECTING\",\"progressPercent\":40}"),null);
            };})); }
            assertTrue(ready.await(5,TimeUnit.SECONDS));go.countDown();List<Integer> codes=new ArrayList<>();
            for(int i=0;i<20;i++) {int code=futures.get(i).get(10,TimeUnit.SECONDS);codes.add(code);assertTrue((i%5==0||i%5==1)?code==409:i%5==2?code==202:i%5==3?code==200:code==200||code==409);}
            assertEquals(1,AI_CALLS.get());assertEquals(1,CANCEL_CALLS.get());assertEquals(1,attempts.countByTaskIdAndActiveTrue(task.getId()));assertNull(current(task).getArchivedAt());
            release.countDown();await(()->current(task).getStatus()==TaskStatus.CANCELLED);assertEquals(1,events.findByTaskOrderByTimestampMsAsc(task).size());assertEquals(5,reviews.countByEvent(old));assertEquals(hash,hash(Path.of(old.getKeyframePath())));
            call(delete(path(task)),owner,200);call(post(path(task)+"/restore"),owner,200);
            record("20_MIXED_LIFECYCLE_REQUESTS",Map.of("taskId",task.getId(),"httpStatuses",codes,"aiCalls",AI_CALLS.get(),"cancelCalls",CANCEL_CALLS.get(),"reviewCount",5,"events",1,"mediaHash",hash));
        } finally {go.countDown();release.countDown();pool.shutdownNow();}
    }

    @Test void archiveAndConcurrentReviewsSerializeWithoutDuplicateRevisionsOrOrphans() throws Exception {
        AnalysisTask task=task();DisputeEvent old=event(task,null,"TERMINAL_RACE");ExecutorService pool=Executors.newFixedThreadPool(20);CountDownLatch go=new CountDownLatch(1);List<Future<Integer>> futures=new ArrayList<>();
        try {
            for(int i=0;i<20;i++) {boolean archive=i==0;futures.add(pool.submit(()->{go.await();return status(archive?delete(path(task)):reviewRequest(old),owner);}));}
            go.countDown();List<Integer> codes=new ArrayList<>();for(var f:futures)codes.add(f.get(10,TimeUnit.SECONDS));assertEquals(200,codes.get(0));assertTrue(codes.stream().allMatch(c->c==200||c==404));
            assertTrue(tasks.existsById(task.getId()));assertTrue(events.existsById(old.getId()));var retained=reviews.findByEventOrderByRevisionDesc(old);assertEquals(retained.size(),retained.stream().map(ReviewRecord::getRevision).distinct().count());
            call(post(path(task)+"/restore"),owner,200);assertEquals(retained.size(),reviews.countByEvent(old));
            record("ARCHIVE_REVIEW_SERIALIZATION",Map.of("taskId",task.getId(),"httpStatuses",codes,"retainedReviews",retained.size(),"eventRetained",true));
        } finally {go.countDown();pool.shutdownNow();}
    }

    @Test void legacyAndInvalidScalarAssociationsAreNotFabricatedOrLeakedAcrossTasks() throws Exception {
        AnalysisTask task=task();event(task,null,"LEGACY");DisputeEvent unknown=event(task,UUID.randomUUID().toString(),"UNKNOWN");
        JsonNode all=body(call(get(path(task)+"?scope=all"),owner,200));assertEquals(2,all.path("events").size());assertTrue(all.path("events").toString().contains("UNKNOWN"));assertEquals(1,body(call(get(path(task)),owner,200)).path("events").size());
        AnalysisTask second=task();TaskAttempt foreign=attempt(second,true);call(get(path(task)+"?attemptId="+foreign.getId()),owner,404);
        task.setCurrentAttemptId(unknown.getAttemptId());tasks.save(task);call(get(path(task)),owner,404);call(get(path(task)+"?scope=all"),owner,200);
        call(post("/api/events/manual").contentType("application/json").content(JSON.writeValueAsString(Map.of("taskId",task.getId(),"eventType","FOUL","timestampMs",1,"summary","fixture","involvedTrackIds",List.of(1)))),owner,409);
        assertEquals(2,events.findByTaskOrderByTimestampMsAsc(task).size());assertEquals(unknown.getAttemptId(),events.findById(unknown.getId()).orElseThrow().getAttemptId());
        record("LEGACY_UNKNOWN_NO_BACKFILL",Map.of("taskId",task.getId(),"currentLegacyCount",1,"allCount",2,"foreignAttemptHttp",404,"invalidManualHttp",409));
    }

    @Test void archiveTransactionFailureRollsBackWithoutTouchingAuditOrMedia() throws Exception {
        AnalysisTask task=task();DisputeEvent old=event(task,null,"ROLLBACK");review(old,owner,200);String hash=hash(Path.of(old.getKeyframePath()));AtomicBoolean fail=new AtomicBoolean(true);
        doAnswer(call->{AnalysisTask t=call.getArgument(0);if(t.getId().equals(task.getId())&&t.getArchivedAt()!=null&&fail.getAndSet(false))throw new DataIntegrityViolationException("synthetic archive failure");return new org.springframework.data.jpa.repository.support.SimpleJpaRepository<AnalysisTask,Long>(AnalysisTask.class,entityManager).save(t);}).when(tasks).save(any());
        var response=java.net.http.HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+port+path(task)))
                .header("Authorization","Bearer "+jwt.issue(owner)).DELETE().build(),java.net.http.HttpResponse.BodyHandlers.discarding());
        assertEquals(500,response.statusCode());assertNull(current(task).getArchivedAt());assertEquals(1,reviews.countByEvent(old));assertEquals(hash,hash(Path.of(old.getKeyframePath())));
        call(delete(path(task)),owner,200);call(post(path(task)+"/restore"),owner,200);record("ARCHIVE_ROLLBACK",Map.of("taskId",task.getId(),"failedHttp",500,"reviews",1,"mediaHash",hash));
    }

    @Test void activeOrUnknownExecutionCannotBeArchivedOrUnlockedThroughRestore() throws Exception {
        AnalysisTask task=task();TaskAttempt attempt=attempt(task,true);attempt.setErrorCode("AI_OUTCOME_UNKNOWN");attempts.save(attempt);task.setCurrentAttemptId(attempt.getId());tasks.save(task);
        call(delete(path(task)),owner,409);assertTrue(attempts.findById(attempt.getId()).orElseThrow().isActive());
        task.setArchivedAt(Instant.now());tasks.save(task);call(post(path(task)+"/restore"),owner,409);assertNotNull(current(task).getArchivedAt());assertTrue(attempts.findById(attempt.getId()).orElseThrow().isActive());
        record("UNKNOWN_OCCUPANCY_NOT_RELEASED",Map.of("taskId",task.getId(),"archive",409,"restore",409,"active",true));
    }

    @Test void missingSyntheticMediaIsVisibleAndCanBeRestoredWithoutChangingAudit() throws Exception {
        AnalysisTask task=task();DisputeEvent old=event(task,null,"MISSING");review(old,owner,200);Path file=Path.of(old.getKeyframePath());assertTrue(file.startsWith(ROOT));Path copy=ROOT.resolve("synthetic-backup-"+UUID.randomUUID()+".png");Files.copy(file,copy);String hash=hash(file);Files.delete(file);
        call(get("/api/files?eventId="+old.getId()+"&kind=keyframe"),owner,404);
        assertTrue(pdf(call(get(path(task)+"/report"),owner,200).getResponse().getContentAsByteArray()).contains("媒体不可用"));assertEquals(1,reviews.countByEvent(old));
        Files.copy(copy,file);assertEquals(hash,hash(file));call(get("/api/files?eventId="+old.getId()+"&kind=keyframe"),owner,200);
        record("SYNTHETIC_MEDIA_RECOVERY",Map.of("taskId",task.getId(),"missingHttp",404,"restoredHttp",200,"restoredHash",hash,"reviewCount",1));
    }

    @Test void physicalFkViolationRollsBackWhileSoftArchiveLeavesNoOrphans() throws Exception {
        AnalysisTask task=task();DisputeEvent old=event(task,null,"FK");review(old,owner,200);
        try(var c=dataSource.getConnection();var statement=c.createStatement()) {c.setAutoCommit(false);assertThrows(java.sql.SQLException.class,()->statement.executeUpdate("DELETE FROM dispute_event WHERE id="+old.getId()));c.rollback();}
        assertTrue(events.existsById(old.getId()));assertEquals(1,reviews.countByEvent(old));call(delete(path(task)),owner,200);assertTrue(tasks.existsById(task.getId()));assertTrue(events.existsById(old.getId()));assertEquals(1,reviews.countByEvent(old));
        record("FK_RESTRICT_AND_ARCHIVE",Map.of("taskId",task.getId(),"physicalDeleteRejected",true,"taskRows",1,"eventRows",1,"reviewRows",1));
    }

    @Test void incrementalSchemaKeepsLegacyRowsAndRestrictiveForeignKeys() throws Exception {
        String sql=Files.readString(Path.of(System.getProperty("wbs07.migration")));
        for(boolean legacy:List.of(false,true))try(var c=java.sql.DriverManager.getConnection("jdbc:h2:mem:archive_upgrade_"+UUID.randomUUID());var s=c.createStatement()) {
            s.execute("CREATE TABLE analysis_task(id BIGINT PRIMARY KEY,status VARCHAR(24))");if(legacy)s.execute("INSERT INTO analysis_task VALUES(7,'FAILED')");
            for(String command:sql.replaceAll("(?m)--[^\\r\\n]*","").split(";"))if(!command.isBlank())s.execute(command);
            if(legacy)try(var row=s.executeQuery("SELECT status,archived_at FROM analysis_task WHERE id=7")){assertTrue(row.next());assertEquals("FAILED",row.getString(1));assertNull(row.getObject(2));}
        }
        int count=0;try(var c=dataSource.getConnection()) {for(String table:List.of("ANALYSIS_TASK","DISPUTE_EVENT","REVIEW_RECORD"))try(var rows=c.getMetaData().getImportedKeys(null,"PUBLIC",table)){while(rows.next()){assertEquals(java.sql.DatabaseMetaData.importedKeyRestrict,rows.getInt("DELETE_RULE"));count++;}}}
        assertEquals(4,count);record("ARCHIVE_SCHEMA_MIGRATION",Map.of("legacyVisible",true,"restrictiveForeignKeys",count,"cascadeAdded",false));
    }

    @Test void gappedLegacyReviewRevisionsContinueFromMaximumWithoutRewritingHistory() throws Exception {
        AnalysisTask task=task();DisputeEvent old=event(task,null,"GAPPED_REVIEW");review(old,owner,200);
        ReviewRecord prior=reviews.findByEventOrderByRevisionDesc(old).get(0);prior.setRevision(7);reviews.save(prior);
        review(old,owner,200);var retained=reviews.findByEventOrderByRevisionDesc(old);
        assertEquals(List.of(8,7),retained.stream().map(ReviewRecord::getRevision).toList());
        assertEquals(prior.getId(),retained.get(1).getId());
        record("GAPPED_REVIEW_REVISION",Map.of("taskId",task.getId(),"priorRevision",7,"nextRevision",8,"oldRecordRetained",true));
    }

    private AnalysisTask task() throws Exception {Path input=ROOT.resolve("uploads/"+UUID.randomUUID()+".mp4");Files.createDirectories(input.getParent());Files.copy(Path.of(System.getProperty("wbs02.mp4")),input);VideoAsset v=new VideoAsset();v.setOwner(owner);v.setTitle("synthetic");v.setCompetition("fixture");v.setTeams("fixture");v.setOriginalPath(input.toString());v=videos.save(v);AnalysisTask t=new AnalysisTask();t.setVideo(v);t.setStatus(TaskStatus.FAILED);return service.createTask(t);}
    private TaskAttempt attempt(AnalysisTask task,boolean active) {TaskAttempt a=new TaskAttempt();a.setTaskId(task.getId());a.setOwnerInstance(UUID.randomUUID().toString());a.setStatus(TaskStatus.FAILED);a.setActive(active);return attempts.save(a);}
    private DisputeEvent event(AnalysisTask task,String attempt,String external) throws Exception {Path image=Path.of(task.getOutputDir()).resolve(UUID.randomUUID()+".png");ImageIO.write(new BufferedImage(80,60,BufferedImage.TYPE_INT_RGB),"png",image.toFile());DisputeEvent e=new DisputeEvent();e.setTask(task);e.setAttemptId(attempt);e.setExternalId(external);e.setEventType(EventType.FOUL);e.setRiskLevel(RiskLevel.UNCERTAIN);e.setSummary("synthetic");e.setKeyframePath(image.toString());e.setClipPath(task.getVideo().getOriginalPath());e.setInvolvedTrackIds("1");return events.save(e);}
    private UserAccount user(Role role) {UserAccount u=new UserAccount();u.setUsername("history_"+UUID.randomUUID());u.setDisplayName("synthetic");u.setRole(role);u.setEnabled(true);u.setPasswordHash("unusable");return users.save(u);}
    private String path(AnalysisTask task) {return "/api/tasks/"+task.getId();}
    private AnalysisTask current(AnalysisTask task) {return tasks.findById(task.getId()).orElseThrow();}
    private MockHttpServletRequestBuilder reviewRequest(DisputeEvent event) {return post("/api/events/"+event.getId()+"/review").contentType("application/json").content("{\"result\":\"UNCERTAIN\",\"reasonCode\":\"FIXTURE\"}");}
    private void review(DisputeEvent event,UserAccount actor,int expected) throws Exception {call(reviewRequest(event),actor,expected);}
    private int status(MockHttpServletRequestBuilder request,UserAccount actor) throws Exception {if(actor!=null)request.header("Authorization","Bearer "+jwt.issue(actor));return mvc.perform(request).andReturn().getResponse().getStatus();}
    private MvcResult call(MockHttpServletRequestBuilder request,UserAccount actor,int expected) throws Exception {if(actor!=null)request.header("Authorization","Bearer "+jwt.issue(actor));MvcResult result=mvc.perform(request).andReturn();assertEquals(expected,result.getResponse().getStatus());return result;}
    private JsonNode body(MvcResult result) throws Exception {return JSON.readTree(result.getResponse().getContentAsByteArray());}
    private String pdf(byte[] bytes) throws Exception {try(var reader=new com.lowagie.text.pdf.PdfReader(bytes)){StringBuilder text=new StringBuilder();var extractor=new com.lowagie.text.pdf.parser.PdfTextExtractor(reader);for(int i=1;i<=reader.getNumberOfPages();i++)text.append(extractor.getTextFromPage(i));return text.toString();}}
    private static String hash(Path p) throws Exception {return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));}
    private synchronized void record(String id,Map<String,Object> values) throws Exception {Map<String,Object> row=new LinkedHashMap<>(values);row.put("id",id);row.put("outcome","PASS");row.put("at",Instant.now().toString());row.put("layer","REAL_SPRING_FILTER_MOCKMVC_DB_AND_LOOPBACK_AI");String destination=System.getProperty("wbs07.evidence");if(destination!=null)Files.writeString(Path.of(destination),JSON.writeValueAsString(row)+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}
    private static void await(BooleanSupplier condition) throws Exception {long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);while(!condition.getAsBoolean()&&System.nanoTime()<until)Thread.sleep(10);assertTrue(condition.getAsBoolean());}
    private static Path temporary() {try{return Files.createTempDirectory("framecall-history-");}catch(Exception e){throw new IllegalStateException(e);}}
    private static HttpServer server() {try {HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(Executors.newCachedThreadPool(r->{Thread t=new Thread(r,"history-fake-ai");t.setDaemon(true);return t;}));
        server.createContext("/internal/analyze",e->{try {AI_CALLS.incrementAndGet();var payload=JSON.readValue(e.getRequestBody(),Map.class);grant=e.getRequestHeaders().getFirst("X-Callback-Token");entered.countDown();release.await(12,TimeUnit.SECONDS);Path out=Path.of(payload.get("outputDir").toString());Files.createDirectories(out);Path image=out.resolve("keyframe.png");ImageIO.write(new BufferedImage(80,60,BufferedImage.TYPE_INT_RGB),"png",image.toFile());Map<String,Object> item=new LinkedHashMap<>(Map.of("externalId","AI_NEW_TAG","eventType","FOUL","riskLevel","UNCERTAIN","timestampMs",1,"windowStartMs",0,"windowEndMs",2,"summary","synthetic","confidence",0.1,"keyframePath",image.toString(),"clipPath",image.toString()));item.put("involvedTrackIds",List.of(1));item.put("evidenceMetrics",List.of());item.put("trackBoxes",List.of());byte[] response=JSON.writeValueAsBytes(Map.of("status","SUCCEEDED","events",List.of(item),"resultJsonPath",out.resolve("result.json").toString()));e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(reject?503:200,response.length);e.getResponseBody().write(response);}catch(Exception ignored){}finally{e.close();}});
        server.createContext("/internal/tasks/",e->{CANCEL_CALLS.incrementAndGet();e.sendResponseHeaders(200,-1);e.close();});server.start();return server;
    }catch(Exception e){throw new IllegalStateException(e);}}
}
