package com.footballref.assist;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.footballref.assist.domain.*;
import com.footballref.assist.domain.enums.*;
import com.footballref.assist.repository.*;
import com.footballref.assist.service.JwtService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Synthetic contracts in an isolated in-memory DB, excluded from real pilot counts. */
@SpringBootTest
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
class ContactAnnotationTest {
    static final Path ROOT=temporary();
    static final byte[] BYTES="Synthetic media contract; not football ground truth".getBytes();
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JwtService jwt;
    @Autowired UserAccountRepository users; @Autowired AnalysisTaskRepository tasks; @Autowired DisputeEventRepository events;
    UserAccount owner,other; long taskId,eventId;
    static Path temporary(){try{return Files.createTempDirectory("p5-contract-");}catch(Exception e){throw new IllegalStateException(e);}}
    @DynamicPropertySource static void config(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",()->"jdbc:h2:mem:p5contracts;DB_CLOSE_DELAY=-1");
        p.add("spring.datasource.driver-class-name",()->"org.h2.Driver");p.add("spring.jpa.hibernate.ddl-auto",()->"create-drop");p.add("app.storage-root",ROOT::toString);
    }
    UserAccount user(){var u=new UserAccount();u.setUsername("p5-"+UUID.randomUUID());u.setDisplayName("Synthetic fixture account");u.setPasswordHash("not-login-data");u.setEnabled(true);u.setRole(Role.REFEREE);return users.save(u);}
    ObjectNode fixture() throws Exception {
        var b=json.createObjectNode();b.put("schema","framecall.contact-pilot.v1").put("method","pilot-preparation");
        b.putObject("source").put("matchId","M1").put("partId","P1").put("segmentId",0).put("event","Contract").put("relativePath","match/part.mp4");
        b.putObject("summary").put("samples",1);
        var c=b.putArray("clips").addObject();c.put("clipId","pilot-01").put("file","pilot-01.mp4").put("previewFile","pilot-01.jpg")
                .put("firstSourceTimeMs",1000).put("lastSourceTimeMs",1240).put("encodedDurationMs",270);
        var frames=c.putArray("sourceFrames");
        for(int pts:new int[]{90000,93000,99000,101700,111600}) frames.addObject().put("sourcePts",pts).put("sourceTimeBase","1/90000").put("timeMs",pts/90.0).put("durationMs",30);
        var s=b.putArray("samples").addObject();s.put("sampleId","P5-S01").put("clipId","pilot-01").put("annotationStatus","UNLABELED")
                .putNull("contestStartTimeMs").putNull("firstContactTimeMs").putNull("contestEndTimeMs").putNull("contactVisibility");
        s.putArray("annotations");s.putArray("reviews");
        var media=b.putArray("media");for(String name:List.of("pilot-01.mp4","pilot-01.jpg")) media.addObject().put("file",name)
                .put("sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(BYTES)));
        return b;
    }
    @BeforeEach void setup() throws Exception {
        owner=user();other=user();var request=multipart("/api/videos/contact-import")
                .file(new MockMultipartFile("manifest","bundle.json","application/json",json.writeValueAsBytes(fixture())));
        for(String name:List.of("pilot-01.mp4","pilot-01.jpg"))request.file(new MockMultipartFile("media",name,"application/octet-stream",BYTES));
        var result=mvc.perform(request.header("Authorization","Bearer "+jwt.issue(owner))).andExpect(status().isOk()).andReturn();
        taskId=json.readTree(result.getResponse().getContentAsByteArray()).path("task").path("id").asLong();
        eventId=events.findByTaskOrderByTimestampMsAsc(tasks.findById(taskId).orElseThrow()).get(0).getId();
    }
    ObjectNode annotation(String visibility) {
        var r=json.createObjectNode();r.put("recordType","HUMAN").put("status","SUBMITTED").put("sceneType","CONTEST")
                .put("expectedRevision",0).put("contestStartTimeMs",1000).put("contestEndTimeMs",1240)
                .put("humanObserved",true).put("contactVisibility",visibility).put("note","Synthetic contract only");
        if(visibility.equals("VISIBLE_CONTACT"))r.put("firstContactTimeMs",1100);else r.putNull("firstContactTimeMs");return r;
    }
    JsonNode save(ObjectNode request,int expected) throws Exception {
        var result=mvc.perform(post("/api/events/"+eventId+"/annotations").header("Authorization","Bearer "+jwt.issue(owner))
                .contentType("application/json").content(json.writeValueAsBytes(request))).andExpect(status().is(expected)).andReturn();
        return json.readTree(result.getResponse().getContentAsByteArray());
    }
    JsonNode exported() throws Exception {return json.readTree(mvc.perform(get("/api/tasks/"+taskId+"/contact-annotations")
            .header("Authorization","Bearer "+jwt.issue(owner))).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());}

    @Test void preparationIsUnlabeledAndNeverImportsReviewOrAlgorithmTruth() throws Exception {
        var e=exported();assertEquals(0,e.path("summary").path("humanAnnotatedSamples").asInt());assertEquals(1,e.path("summary").path("unlabeledSamples").asInt());
        assertTrue(e.path("samples").get(0).path("annotations").isEmpty());assertTrue(e.path("samples").get(0).path("reviews").isEmpty());
        assertEquals(90000,e.path("samples").get(0).path("sourceFrameBounds").path("first").path("sourcePts").asInt());
        mvc.perform(get("/api/events/"+eventId+"/annotations").header("Authorization","Bearer "+jwt.issue(other))).andExpect(status().isNotFound());
    }
    @Test void realFramePtsAreKeptAndNoContactKeepsFirstContactNull() throws Exception {
        var visible=save(annotation("VISIBLE_CONTACT"),200);
        assertEquals(99000,visible.path("annotation").path("sourcePoints").path("firstContact").path("sourcePts").asInt());
        assertEquals("1/90000",visible.path("annotation").path("sourcePoints").path("firstContact").path("sourceTimeBase").asText());
        var negative=save(annotation("NO_VISIBLE_CONTACT"),200);assertTrue(negative.path("annotation").path("firstContactTimeMs").isNull());
        var e=exported();assertEquals(2,e.path("summary").path("humanSubmittedRecords").asInt());assertEquals(1,e.path("summary").path("humanAnnotatedSamples").asInt());
    }
    @Test void falseContactTimesInvalidOrderAndNonFrameTimesAreRejected() throws Exception {
        var r=annotation("NO_VISIBLE_CONTACT");r.put("firstContactTimeMs",1100);save(r,400);
        r=annotation("VISIBLE_CONTACT");r.put("firstContactTimeMs",2000);save(r,400);
        r=annotation("VISIBLE_CONTACT");r.put("contestStartTimeMs",1130);save(r,400);
        r=annotation("VISIBLE_CONTACT");r.put("contestEndTimeMs",1000);save(r,400);
        r=annotation("VISIBLE_CONTACT");r.put("firstContactTimeMs",1050);save(r,400);
        r=annotation("VISIBLE_CONTACT");r.put("humanObserved",false);save(r,400);
        assertTrue(exported().path("samples").get(0).path("annotations").isEmpty());
    }
    @Test void revisionsAreAppendOnlyAndRefereeReviewDoesNotOverwriteOriginalLabel() throws Exception {
        var first=save(annotation("NO_VISIBLE_CONTACT"),200);
        var changed=annotation("VISIBLE_CONTACT");changed.put("annotationKey",first.path("annotationKey").asText()).put("expectedRevision",1);
        var second=save(changed,200);assertEquals(2,second.path("revision").asInt());save(changed,409);
        mvc.perform(post("/api/events/"+eventId+"/review").header("Authorization","Bearer "+jwt.issue(owner)).contentType("application/json")
                .content(json.writeValueAsBytes(Map.of("result","NO_VISIBLE_CONTACT","reasonCode","SYNTHETIC_REVIEW","annotationRevisionId",second.path("id").asLong())))).andExpect(status().isOk());
        var sample=exported().path("samples").get(0);
        assertEquals(2,sample.path("annotations").size());assertEquals("VISIBLE_CONTACT",sample.path("annotations").get(0).path("annotation").path("contactVisibility").asText());
        assertEquals("NO_VISIBLE_CONTACT",sample.path("annotations").get(1).path("annotation").path("contactVisibility").asText());
        assertEquals("NO_VISIBLE_CONTACT",sample.path("reviews").get(0).path("result").asText());assertEquals("REVIEWED",sample.path("annotations").get(0).path("reviewStatus").asText());
    }
    @Test void draftAndFunctionRecordsNeverCountAsHumanTruthOrAcceptUnlinkedReviews() throws Exception {
        var draft=json.createObjectNode();draft.put("recordType","HUMAN").put("status","DRAFT").put("expectedRevision",0);
        var row=save(draft,200);assertTrue(row.path("annotation").path("contestStartTimeMs").isNull());
        var partial=annotation("VISIBLE_CONTACT");partial.put("status","DRAFT").putNull("contestStartTimeMs").putNull("contestEndTimeMs");
        assertEquals(1100,save(partial,200).path("annotation").path("firstContactTimeMs").asInt());
        var test=annotation("VISIBLE_CONTACT");test.put("recordType","FUNCTION_TEST");save(test,200);
        var e=exported();assertEquals(0,e.path("summary").path("humanAnnotatedSamples").asInt());assertEquals(0,e.path("summary").path("formalGroundTruth").asInt());
        mvc.perform(post("/api/events/"+eventId+"/review").header("Authorization","Bearer "+jwt.issue(owner)).contentType("application/json")
                .content("{\"result\":\"INSUFFICIENT_VIEW\",\"reasonCode\":\"NO_ANNOTATION\"}")).andExpect(status().isBadRequest());
    }
    @Test void sourceWithoutContestDoesNotRequireFabricatedBoundaries() throws Exception {
        var r=annotation("NO_VISIBLE_CONTACT");r.put("sceneType","NO_CONTEST").putNull("contestStartTimeMs").putNull("contestEndTimeMs");
        var row=save(r,200);assertTrue(row.path("annotation").path("sourcePoints").path("contestStart").isNull());
    }
}
