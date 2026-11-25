package kz.unistudy;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
@SpringBootTest @AutoConfigureMockMvc
class Gradebook54Test {
 @DynamicPropertySource static void isolate(DynamicPropertyRegistry p) throws Exception {var r=Files.createTempDirectory("grades54");p.add("UNISTUDY_DB",()->r.resolve("test.db").toString());p.add("UNISTUDY_STORAGE",()->r.resolve("files").toString());}
 @Autowired MockMvc mvc; @Autowired JdbcTemplate db;
 @Test void weeklyPreservesZeroAbsenceMissingAndAuditsChanges() throws Exception {
  db.update("INSERT INTO groups(id,name) VALUES(1,'G')");
  for(int id=1;id<=2;id++){db.update("INSERT INTO users(id,full_name,username,password_hash,role,active,approved,must_change_password,auth_version,group_id) VALUES(?,?,?,'unused',?,1,1,0,0,1)",id,"User"+id,"u"+id,id==1?"admin":"student");db.update("INSERT INTO auth_sessions(user_id,auth_version,access_hash,refresh_hash,access_expires,refresh_expires) VALUES(?,0,?,?,?,?)",id,Store.digest("t"+id),Store.digest("r"+id),Store.now()+300,Store.now()+300);}
  db.update("INSERT INTO subjects(id,code,name,teacher_name,description,group_ids,teacher_ids) VALUES(1,'G','G','','','[1]','[]')");
  for(int id=1;id<=4;id++) db.update("INSERT INTO assessments(id,subject_id,group_id,title,date,week,kind,version) VALUES(?,1,1,'Work','2026-11-24',1,'lab',1)",id);
  db.update("INSERT INTO grade_entries(assessment_id,student_id,value) VALUES(1,2,'0'),(2,2,'100'),(3,2,'NP')");
  db.update("UPDATE assessments SET kind='Зертханалық' WHERE id=2");
  mvc.perform(get("/api/gradebook?subject_id=1&group_id=1&kind=lab").header("Authorization","Bearer t1")).andExpect(status().isOk()).andExpect(jsonPath("$.assessments.length()").value(4));
  mvc.perform(get("/api/gradebook?subject_id=1&group_id=1").header("Authorization","Bearer t1")).andExpect(status().isOk()).andExpect(jsonPath("$.weekly[0].weeks[0].average").value(50.0)).andExpect(jsonPath("$.weekly[0].weeks[0].np_count").value(1)).andExpect(jsonPath("$.weekly[0].weeks[0].missing_count").value(1)).andExpect(jsonPath("$.weekly[0].weeks[0].assessments.length()").value(4)).andExpect(jsonPath("$.summaries[0].final").isEmpty());
  mvc.perform(get("/api/gradebook?subject_id=1&group_id=1").header("Authorization","Bearer t2"))
      .andExpect(status().isOk()).andExpect(jsonPath("$.kind_weekly.length()").value(3))
      .andExpect(jsonPath("$.kind_weekly[0].kind").value("lecture"))
      .andExpect(jsonPath("$.kind_weekly[0].weeks[0].average").isEmpty())
      .andExpect(jsonPath("$.kind_weekly[1].kind").value("sro"))
      .andExpect(jsonPath("$.kind_weekly[2].kind").value("lab"))
      .andExpect(jsonPath("$.kind_weekly[2].weeks[0].average").value(50.0))
      .andExpect(jsonPath("$.kind_weekly[2].weeks[0].assessments.length()").value(4))
      .andExpect(jsonPath("$.kind_weekly[2].period1_average").value(50.0))
      .andExpect(jsonPath("$.kind_weekly[2].period2_average").isEmpty());
  mvc.perform(put("/api/assessments/1/grades").header("Authorization","Bearer t1").contentType("application/json").content("{\"version\":1,\"entries\":[{\"student_id\":2,\"value\":null}]}")).andExpect(status().isOk());
  assertEquals("0",db.queryForObject("SELECT old_value FROM grade_audit WHERE assessment_id=1",String.class));
  assertNull(db.queryForObject("SELECT new_value FROM grade_audit WHERE assessment_id=1",String.class));
  mvc.perform(put("/api/gradebook/components").header("Authorization","Bearer t2").contentType("application/json").content("{\"subject_id\":1,\"group_id\":1,\"student_id\":2,\"version\":0,\"rk1\":90}")).andExpect(status().isForbidden());
  mvc.perform(put("/api/gradebook/components").header("Authorization","Bearer t1").contentType("application/json").content("{\"subject_id\":1,\"group_id\":1,\"student_id\":2,\"version\":0,\"rk1\":90}")).andExpect(status().isOk());
  mvc.perform(put("/api/gradebook/components").header("Authorization","Bearer t1").contentType("application/json").content("{\"subject_id\":1,\"group_id\":1,\"student_id\":2,\"version\":0,\"exam\":100}")).andExpect(status().isConflict());
  assertNull(db.queryForObject("SELECT exam FROM grade_components WHERE student_id=2",Integer.class));
  mvc.perform(get("/api/gradebook?subject_id=1&group_id=1&kind=&week=").header("Authorization","Bearer t1")).andExpect(status().isOk());
  mvc.perform(get("/api/gradebook?subject_id=1&group_id=1&kind=lecture").header("Authorization","Bearer t2")).andExpect(status().isOk()).andExpect(jsonPath("$.assessments.length()").value(0)).andExpect(jsonPath("$.summaries[0].rk1").value(90));
  db.update("INSERT INTO materials(id,subject_id,title,description,week,kind,section,group_ids,published,created_at) VALUES(1,1,'Lecture','',2,'lecture','resource','[1]',1,'now')");
  mvc.perform(post("/api/assessments").header("Authorization","Bearer t1").contentType("application/json").content("{\"material_id\":1,\"group_id\":1,\"date\":\"2026-11-24\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.kind").value("lecture")).andExpect(jsonPath("$.material_id").value(1));
  db.update("INSERT INTO schedule(id,subject_id,teacher_id,group_id,date,start_time,end_time,room,room_key,kind,week) VALUES(1,1,1,1,'2026-11-24','09:00','10:00','1','1','lecture',2)");
  mvc.perform(post("/api/assessments").header("Authorization","Bearer t1").contentType("application/json").content("{\"schedule_id\":1,\"group_id\":1}")).andExpect(status().isOk()).andExpect(jsonPath("$.schedule_id").value(1));
  db.update("UPDATE schedule SET kind='lab' WHERE id=1");
  mvc.perform(post("/api/assessments").header("Authorization","Bearer t1").contentType("application/json").content("{\"schedule_id\":1,\"group_id\":1}")).andExpect(status().isUnprocessableEntity());
  db.update("UPDATE grade_entries SET is_demo=1,source='demo54' WHERE assessment_id=2 AND student_id=2");
  mvc.perform(put("/api/assessments/2/grades").header("Authorization","Bearer t1").contentType("application/json").content("{\"version\":1,\"entries\":[{\"student_id\":2,\"value\":\"85\"}]}")).andExpect(status().isOk());
  assertEquals(0,db.queryForObject("SELECT is_demo FROM grade_entries WHERE assessment_id=2 AND student_id=2",Integer.class));
  assertEquals("manual",db.queryForObject("SELECT source FROM grade_entries WHERE assessment_id=2 AND student_id=2",String.class));

  db.update("UPDATE materials SET week=NULL WHERE id=1");
  mvc.perform(post("/api/assessments").header("Authorization","Bearer t1").contentType("application/json").content("{\"material_id\":1,\"group_id\":1,\"date\":\"2026-11-24\",\"week\":5}")).andExpect(status().isOk()).andExpect(jsonPath("$.week").value(5));
  assertNull(db.queryForObject("SELECT week FROM materials WHERE id=1",Integer.class));

  mvc.perform(get("/api/gradebook?subject_id=1&group_id=1&mode=real").header("Authorization","Bearer t2")).andExpect(status().isOk()).andExpect(jsonPath("$.weekly[0].weeks[0].average").value(85.0)).andExpect(jsonPath("$.summaries[0].rk1").value(90));
  mvc.perform(get("/api/gradebook?subject_id=1&group_id=1&mode=demo").header("Authorization","Bearer t2")).andExpect(status().isOk()).andExpect(jsonPath("$.weekly[0].weeks[0].average").value(85.0)).andExpect(jsonPath("$.summaries[0].rk1").value(90));

  assertEquals("Зертханалық",db.queryForObject("SELECT kind FROM assessments WHERE id=2",String.class));

  db.update("UPDATE assessments SET archived=1,merged_into=2 WHERE id=3");
  mvc.perform(put("/api/assessments/3/grades").header("Authorization","Bearer t1").contentType("application/json").content("{\"version\":1,\"entries\":[]}")).andExpect(status().isConflict());
  mvc.perform(delete("/api/assessments/3").header("Authorization","Bearer t1")).andExpect(status().isConflict());
  mvc.perform(get("/api/gradebook?subject_id=1&group_id=1&mode=demo").header("Authorization","Bearer t2")).andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("unified")).andExpect(jsonPath("$.weekly[0].weeks[0].np_count").value(0));

  db.update("INSERT INTO users(id,full_name,username,password_hash,role,active,approved,must_change_password,auth_version,group_id) VALUES(3,'Third','third','unused','student',1,1,0,0,1)");
  db.update("INSERT INTO assessments(id,subject_id,group_id,title,date,week,kind,version) VALUES(900,1,1,'Generated','2026-11-24',3,'lab',1)");
  db.update("INSERT INTO grade_entries(assessment_id,student_id,value,generated_origin,source) VALUES(900,2,'80',1,'demo54'),(900,3,'80',1,'demo54')");
  mvc.perform(put("/api/assessments/900/grades").header("Authorization","Bearer t1").contentType("application/json").content("{\"version\":1,\"entries\":[{\"student_id\":2,\"value\":\"85\"},{\"student_id\":3,\"value\":\"80\"}]}")).andExpect(status().isOk());
  assertEquals(0,db.queryForObject("SELECT generated_origin FROM grade_entries WHERE assessment_id=900 AND student_id=2",Integer.class));
  assertEquals(1,db.queryForObject("SELECT generated_origin FROM grade_entries WHERE assessment_id=900 AND student_id=3",Integer.class));
  assertEquals("demo54",db.queryForObject("SELECT source FROM grade_entries WHERE assessment_id=900 AND student_id=3",String.class));
  assertEquals(1,db.queryForObject("SELECT count(*) FROM grade_audit WHERE assessment_id=900",Integer.class));

 }
}
