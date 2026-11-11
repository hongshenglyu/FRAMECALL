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

/** Synthetic contract bytes test import/storage/ownership, not video decoding or football accuracy. */
@SpringBootTest
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
class ContactImportTest {
    static final Path ROOT=temporary();
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserAccountRepository users;
    @Autowired AnalysisTaskRepository tasks;
    @Autowired DisputeEventRepository events;
    @Autowired ReviewRecordRepository reviews;
    @Autowired JwtService jwt;
    UserAccount owner,other;
    static final byte[] MEDIA="Synthetic contract bytes; not a football playback test".getBytes();

    @DynamicPropertySource static void config(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",()->"jdbc:h2:mem:p4contracts;DB_CLOSE_DELAY=-1");
        p.add("spring.datasource.driver-class-name",()->"org.h2.Driver");
        p.add("spring.jpa.hibernate.ddl-auto",()->"create-drop");
        p.add("app.storage-root",ROOT::toString);
    }
    @BeforeEach void setup() {
        owner=user(); other=user();
    }
    UserAccount user() {
        UserAccount u=new UserAccount(); u.setUsername("p4-"+UUID.randomUUID()); u.setDisplayName("Synthetic reviewer");
        u.setPasswordHash("not-a-login-password"); u.setEnabled(true); u.setRole(Role.REFEREE); return users.save(u);
    }
    static Path temporary() { try {return Files.createTempDirectory("framecall-p4-contract-");} catch(Exception e){throw new IllegalStateException(e);} }
    ObjectNode bundle() throws Exception {
        ObjectNode b=json.createObjectNode(); b.put("schema","framecall.contact-clips.v1"); b.put("method","combined");
        b.putObject("source").put("matchId","M1").put("partId","P1").put("segmentId",0).put("event","Synthetic contract")
                .put("relativePath","match/part.mp4").put("sampleStartTimeMs",0).put("sampleEndTimeMs",10000);
        b.putObject("summary").put("originalCandidates",2);
        ObjectNode clip=b.putArray("clips").addObject(); clip.put("clipId","window-01"); clip.put("file","window-01.mp4");
        clip.put("firstSourceTimeMs",500); clip.put("encodedDurationMs",3000); clip.putArray("eventIds").add("CONTACT-0000000000000001");
        ArrayNode bindings=b.putArray("events");
        for(int i=1;i<=2;i++) {
            ObjectNode binding=bindings.addObject(), e=binding.putObject("candidate");
            double peak=i==1?1000:5000;
            e.put("eventId","CONTACT-000000000000000"+i).put("matchId","M1").put("partId","P1").put("segmentId",0)
                    .put("startTimeMs",peak-50).put("peakTimeMs",peak).put("endTimeMs",peak+50)
                    .put("peakSourcePts",(int)peak).put("sourceTimeBase","1/1000").put("candidateScore",0.5)
                    .put("minNormalizedDistance",0.2).put("contactStatus","NOT_DETERMINED").put("foulDecision","NOT_ASSESSED");
            e.putArray("trackParticipants").addObject().put("trackKey","M1:P1:S0:T1");
            e.putObject("peakEvidence").putNull("closingSpeedHeightPerSecond").putNull("relativeVelocityChangeHeightPerSecond")
                    .put("closingUnavailableReason","INSUFFICIENT_CONTIGUOUS_SAMPLES").put("velocityChangeUnavailableReason","INSUFFICIENT_CONTIGUOUS_SAMPLES");
            if(i==1) binding.put("clipId","window-01").put("keyframe","CONTACT-0000000000000001.jpg").put("clipPeakTimeMs",500);
            else binding.putNull("clipId").putNull("keyframe").putNull("clipPeakTimeMs");
        }
        ArrayNode media=b.putArray("media");
        for(String name:List.of("window-01.mp4","CONTACT-0000000000000001.jpg")) media.addObject().put("file",name)
                .put("sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(MEDIA)));
        return b;
    }
    JsonNode imported() throws Exception {
        var request=multipart("/api/videos/contact-import").file(new MockMultipartFile("manifest","bundle.json","application/json",json.writeValueAsBytes(bundle())));
        for(String name:List.of("window-01.mp4","CONTACT-0000000000000001.jpg")) request.file(new MockMultipartFile("media",name,"application/octet-stream",MEDIA));
        var response=mvc.perform(request.header("Authorization","Bearer "+jwt.issue(owner))).andExpect(status().isOk()).andReturn();
        return json.readTree(response.getResponse().getContentAsByteArray());
    }
    @Test void importedEventsKeepOriginalIdsAndUnavailableEvidenceAndAuthorizedMedia() throws Exception {
        long taskId=imported().path("task").path("id").asLong();
        var task=tasks.findById(taskId).orElseThrow();
        var saved=events.findByTaskOrderByTimestampMsAsc(task);
        assertEquals(2,saved.size()); assertEquals("CONTACT-0000000000000001",saved.get(0).getExternalId());
        assertEquals("",saved.get(1).getClipPath());
        assertTrue(json.readTree(saved.get(0).getEvidenceJson()).get(1).get("value").isNull());
        assertTrue(Files.exists(Path.of(task.getResultJsonPath())));
        mvc.perform(get("/api/files").param("eventId",saved.get(0).getId().toString()).param("kind","clip")
                .header("Authorization","Bearer "+jwt.issue(owner))).andExpect(status().isOk()).andExpect(content().bytes(MEDIA));
        mvc.perform(get("/api/files").param("eventId",saved.get(0).getId().toString()).param("kind","clip")
                .header("Authorization","Bearer "+jwt.issue(other))).andExpect(status().isNotFound());
        mvc.perform(get("/api/files").param("eventId",saved.get(1).getId().toString()).param("kind","clip")
                .header("Authorization","Bearer "+jwt.issue(owner))).andExpect(status().isNotFound());
    }
    @Test void missingMediaIsRejectedBeforeTaskCreation() throws Exception {
        long count=tasks.count();
        mvc.perform(multipart("/api/videos/contact-import")
                .file(new MockMultipartFile("manifest","bundle.json","application/json",json.writeValueAsBytes(bundle())))
                .file(new MockMultipartFile("media","window-01.mp4","video/mp4",MEDIA))
                .header("Authorization","Bearer "+jwt.issue(owner))).andExpect(status().isBadRequest());
        assertEquals(count,tasks.count());
    }
    @Test void threeContactVisibilityResultsPersistAndCannotBeMisstatedAsFoulConfirmation() throws Exception {
        long taskId=imported().path("task").path("id").asLong();
        long eventId=events.findByTaskOrderByTimestampMsAsc(tasks.findById(taskId).orElseThrow()).get(0).getId();
        for(String result:List.of("VISIBLE_CONTACT","NO_VISIBLE_CONTACT","INSUFFICIENT_VIEW")) {
            mvc.perform(post("/api/events/"+eventId+"/review").header("Authorization","Bearer "+jwt.issue(owner))
                    .contentType("application/json").content(json.writeValueAsBytes(Map.of("result",result,"reasonCode","INTEGRATION_TEST","note","Synthetic contract check; not referee ground truth"))))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.result").value(result));
        }
        mvc.perform(get("/api/events/"+eventId).header("Authorization","Bearer "+jwt.issue(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.reviews.length()").value(3))
                .andExpect(jsonPath("$.reviews[0].result").value("INSUFFICIENT_VIEW"));
        mvc.perform(post("/api/events/"+eventId+"/review").header("Authorization","Bearer "+jwt.issue(owner))
                .contentType("application/json").content("{\"result\":\"CONFIRMED\",\"reasonCode\":\"NOT_A_FOUL_DECISION\"}"))
                .andExpect(status().isBadRequest());
    }
}
