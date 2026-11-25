package kz.unistudy;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

@org.springframework.boot.test.context.SpringBootTest
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
class DeadlineApiTest {
  static final String HASH = ServerTest.HASH;
  @org.springframework.beans.factory.annotation.Autowired org.springframework.test.web.servlet.MockMvc mvc;
  @org.springframework.beans.factory.annotation.Autowired org.springframework.jdbc.core.JdbcTemplate db;
  @org.springframework.beans.factory.annotation.Autowired com.fasterxml.jackson.databind.ObjectMapper json;
  @org.springframework.test.context.DynamicPropertySource
  static void isolate(org.springframework.test.context.DynamicPropertyRegistry properties) throws Exception {
    var root = java.nio.file.Files.createTempDirectory("unistudy-deadline-test");
    properties.add("UNISTUDY_DB", () -> root.resolve("test.db").toString());
    properties.add("UNISTUDY_STORAGE", () -> root.resolve("files").toString());
  }
  @Test void deadlineRejectsMutationsButKeepsReadsAndExtensionReopens() throws Exception {
    fixture(9700);
    db.update("UPDATE materials SET deadline='2099-01-01' WHERE id=9700");
    mvc.perform(get("/api/materials/9700").header("Authorization","Bearer deadline9701"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.deadline").value("2099-01-01T19:00:00Z"))
        .andExpect(jsonPath("$.submission_closed").value(false));
    db.update("UPDATE materials SET deadline=NULL WHERE id=9700");
    var result = mvc.perform(put("/api/materials/9700/submission").header("Authorization","Bearer deadline9701")
        .contentType("application/json").content("{\"text\":\"first\"}"))
        .andExpect(status().isOk()).andReturn();
    int submission = json.readTree(result.getResponse().getContentAsByteArray()).get("id").asInt();
    var uploaded = mvc.perform(multipart("/api/submissions/"+submission+"/files")
        .file(new MockMultipartFile("file","answer.txt","text/plain","answer".getBytes()))
        .header("Authorization","Bearer deadline9701")).andExpect(status().isOk()).andReturn();
    int file = json.readTree(uploaded.getResponse().getContentAsByteArray()).get("id").asInt();
    db.update("UPDATE materials SET deadline=? WHERE id=9700", "2000-01-01T00:00:00Z");
    mvc.perform(put("/api/materials/9700/submission").header("Authorization","Bearer deadline9701")
        .contentType("application/json").content("{\"text\":\"late\"}")).andExpect(status().isConflict());
    mvc.perform(multipart("/api/submissions/"+submission+"/files")
        .file(new MockMultipartFile("file","late.txt","text/plain","late".getBytes()))
        .header("Authorization","Bearer deadline9701")).andExpect(status().isConflict());
    mvc.perform(delete("/api/submission-files/"+file).header("Authorization","Bearer deadline9701")).andExpect(status().isConflict());
    mvc.perform(get("/api/submission-files/"+file+"/download").header("Authorization","Bearer deadline9701")).andExpect(status().isOk());
    assertEquals("first",db.queryForObject("SELECT text FROM submissions WHERE id=?",String.class,submission));
    mvc.perform(get("/api/subjects/9700/materials").header("Authorization","Bearer deadline9701"))
        .andExpect(jsonPath("$[0].submission_closed").value(true)).andExpect(jsonPath("$[0].student_status").value("submitted"));
    mvc.perform(patch("/api/materials/9700").header("Authorization","Bearer deadline9702")
        .contentType("application/json").content("{\"deadline\":\"2099-01-01T00:00:00Z\"}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.submission_closed").value(false));
    mvc.perform(delete("/api/submission-files/"+file).header("Authorization","Bearer deadline9701")).andExpect(status().isOk());
  }

  @Test void assessmentDerivesMaterialFieldsAndRejectsGuidesAndForeignGroups() throws Exception {
    fixture(9750);
    var body=Store.fields("material_id",9750,"group_id",9750,"date","2026-11-24","title","spoof","kind","exam","week",15);
    mvc.perform(post("/api/assessments").header("Authorization","Bearer deadline9752")
        .contentType("application/json").content(json.writeValueAsBytes(body)))
        .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("Assignment"))
        .andExpect(jsonPath("$.kind").value("lab")).andExpect(jsonPath("$.week").value(2))
        .andExpect(jsonPath("$.material_id").value(9750));
    int assessment = db.queryForObject("SELECT id FROM assessments WHERE material_id=9750",Integer.class);
    db.update("INSERT INTO grade_entries(assessment_id,student_id,value) VALUES(?,9751,'88')",assessment);
    mvc.perform(get("/api/materials/9750").header("Authorization","Bearer deadline9751"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.student_status").value("graded"))
        .andExpect(jsonPath("$.server_now").isString());
    db.update("UPDATE grade_entries SET is_demo=1 WHERE assessment_id=?",assessment);
    mvc.perform(get("/api/materials/9750").header("Authorization","Bearer deadline9751"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.student_status").value("not_submitted"));
    db.update("UPDATE grade_entries SET is_demo=0,generated_origin=1 WHERE assessment_id=?",assessment);
    mvc.perform(get("/api/materials/9750").header("Authorization","Bearer deadline9751"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.student_status").value("not_submitted"));
    mvc.perform(put("/api/assessments/"+assessment+"/grades").header("Authorization","Bearer deadline9752").contentType("application/json").content("{\"version\":1,\"entries\":[{\"student_id\":9751,\"value\":\"87\"}]}")).andExpect(status().isOk());
    mvc.perform(get("/api/materials/9750").header("Authorization","Bearer deadline9751"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.student_status").value("graded"));
    db.update("UPDATE materials SET week=NULL WHERE id=9750");
    mvc.perform(post("/api/assessments").header("Authorization","Bearer deadline9752")
        .contentType("application/json").content(json.writeValueAsBytes(body)))
        .andExpect(status().isOk()).andExpect(jsonPath("$.week").value(15));
    body.put("group_id",9751);
    mvc.perform(post("/api/assessments").header("Authorization","Bearer deadline9752")
        .contentType("application/json").content(json.writeValueAsBytes(body))).andExpect(status().isNotFound());
    body.put("group_id",9750);
    db.update("UPDATE materials SET kind='lecture',section='resource' WHERE id=9750");
    mvc.perform(post("/api/assessments").header("Authorization","Bearer deadline9752")
        .contentType("application/json").content(json.writeValueAsBytes(body))).andExpect(status().isOk()).andExpect(jsonPath("$.kind").value("lecture"));
    db.update("UPDATE materials SET kind='guide' WHERE id=9750");
    mvc.perform(post("/api/assessments").header("Authorization","Bearer deadline9752")
        .contentType("application/json").content(json.writeValueAsBytes(body))).andExpect(status().isUnprocessableEntity());
    mvc.perform(patch("/api/materials/9750").header("Authorization","Bearer deadline9752")
        .contentType("application/json").content("{\"kind\":\"sro\",\"section\":\"resource\"}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.section").value("assignment"));
  }

  @org.springframework.beans.factory.annotation.Autowired java.nio.file.Path storage;

  @Test void crossingDeadlineRollsBackBlobRowAndTimestamp() throws Exception {
    fixture(9780);
    var result = mvc.perform(put("/api/materials/9780/submission").header("Authorization","Bearer deadline9781")
        .contentType("application/json").content("{\"text\":\"original\"}"))
        .andExpect(status().isOk()).andReturn();
    int submission = json.readTree(result.getResponse().getContentAsByteArray()).get("id").asInt();
    String updated = db.queryForObject("SELECT updated_at FROM submissions WHERE id=?",String.class,submission);
    long before;
    try(var files=java.nio.file.Files.list(storage)) { before=files.count(); }
    Instant deadline = Instant.now().plusSeconds(2);
    db.update("UPDATE materials SET deadline=? WHERE id=9780", deadline.toString());
    var copied = new java.util.concurrent.atomic.AtomicBoolean();
    var slowFile = new MockMultipartFile("file","slow.txt","text/plain","slow answer".getBytes()) {
      @Override public java.io.InputStream getInputStream() {
        copied.set(true);
        while(Instant.now().isBefore(deadline.plusMillis(20))) {
          try { Thread.sleep(10); } catch(InterruptedException ex) { throw new RuntimeException(ex); }
        }
        return new java.io.ByteArrayInputStream("slow answer".getBytes());
      }
    };
    mvc.perform(multipart("/api/submissions/"+submission+"/files").file(slowFile)
        .header("Authorization","Bearer deadline9781")).andExpect(status().isConflict());
    assertTrue(copied.get(),"Must exercise the post-copy check, not the initial guard");
    assertEquals(0,db.queryForObject("SELECT count(*) FROM submission_files WHERE submission_id=?",Integer.class,submission));
    assertEquals(updated,db.queryForObject("SELECT updated_at FROM submissions WHERE id=?",String.class,submission));
    try(var files=java.nio.file.Files.list(storage)) { assertEquals(before,files.count()); }
  }

  void fixture(int base) {
    db.update("INSERT INTO groups(id,name) VALUES(?,?)",base,"Deadline group "+base);
    db.update("INSERT INTO groups(id,name) VALUES(?,?)",base+1,"Other deadline group "+base);
    for(int offset: new int[]{1,2}) {
      int uid=base+offset;
      db.update("INSERT INTO users(id,full_name,username,password_hash,role,group_id,active,approved,must_change_password,auth_version) VALUES(?,?,?,?,?,?,1,1,0,0)",uid,"Deadline user","deadline"+uid,HASH,offset==1?"student":"teacher",base);
      db.update("INSERT INTO auth_sessions(user_id,auth_version,access_hash,refresh_hash,access_expires,refresh_expires) VALUES(?,0,?,?,?,?)",uid,Store.digest("deadline"+uid),Store.digest("deadline-refresh"+uid),Store.now()+300,Store.now()+300);
    }
    db.update("INSERT INTO subjects(id,code,name,teacher_name,description,group_ids,teacher_ids) VALUES(?,?,?,?,?,?,?)",base,"DEADLINE"+base,"Deadline","Teacher","","["+base+","+(base+1)+"]","["+(base+2)+"]");
    db.update("INSERT INTO materials(id,subject_id,title,description,week,kind,section,group_ids,published,created_at) VALUES(?,?,'Assignment','',2,'lab','assignment',?,1,?)",base,base,"["+base+"]",Instant.now().toString());
  }
}
