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
class Schedule54Test {
 @DynamicPropertySource static void isolate(DynamicPropertyRegistry p) throws Exception {
  var r=Files.createTempDirectory("schedule54");p.add("UNISTUDY_DB",()->r.resolve("test.db").toString());p.add("UNISTUDY_STORAGE",()->r.resolve("files").toString());
 }
 @Autowired MockMvc mvc; @Autowired JdbcTemplate db;
 @Test void sharedLectureIsOneEventAndConflictsRemainEnforced() throws Exception {
  db.update("INSERT INTO groups(id,name) VALUES(1,'G1'),(2,'G2')");
  for(int id=1;id<=3;id++){
   db.update("INSERT INTO users(id,full_name,username,password_hash,role,active,approved,must_change_password,auth_version,group_id) VALUES(?,?,?,'unused',?,1,1,0,0,2)",id,"User"+id,"u"+id,id==1?"admin":id==2?"teacher":"student");
   db.update("INSERT INTO auth_sessions(user_id,auth_version,access_hash,refresh_hash,access_expires,refresh_expires) VALUES(?,0,?,?,?,?)",id,Store.digest("s"+id),Store.digest("sr"+id),Store.now()+300,Store.now()+300);
  }
  db.update("INSERT INTO subjects(id,code,name,teacher_name,description,group_ids,teacher_ids) VALUES(1,'S','Subject','','','[1,2]','[2]')");
  String body="{\"subject_id\":1,\"teacher_id\":2,\"group_id\":1,\"group_ids\":[1,2],\"date\":\"2026-11-24\",\"start_time\":\"08:00\",\"end_time\":\"08:50\",\"room\":\"101\",\"kind\":\"lecture\",\"week\":1}";
  mvc.perform(post("/api/schedule").header("Authorization","Bearer s1").contentType("application/json").content(body)).andExpect(status().isOk());
  assertEquals(2,db.queryForObject("SELECT count(*) FROM schedule",Integer.class));
  mvc.perform(get("/api/schedule?from_date=2026-11-24&to_date=2026-11-24").header("Authorization","Bearer s2")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].group_ids.length()").value(2));
  mvc.perform(get("/api/schedule?from_date=2026-11-24&to_date=2026-11-24").header("Authorization","Bearer s3")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
  mvc.perform(post("/api/schedule").header("Authorization","Bearer s1").contentType("application/json").content(body)).andExpect(status().isConflict());
  mvc.perform(patch("/api/schedule/1").header("Authorization","Bearer s1").contentType("application/json").content(body.replace("08:00","09:00").replace("08:50","09:50"))).andExpect(status().isOk());
  assertEquals(2,db.queryForObject("SELECT count(*) FROM schedule WHERE start_time='09:00'",Integer.class));
  mvc.perform(post("/api/assessments").header("Authorization","Bearer s2").contentType("application/json").content("{\"schedule_id\":1,\"group_id\":2}"))
      .andExpect(status().isOk()).andExpect(jsonPath("$.group_id").value(2)).andExpect(jsonPath("$.schedule_id").value(2));
  mvc.perform(delete("/api/schedule/1").header("Authorization","Bearer s3")).andExpect(status().isForbidden());
  mvc.perform(delete("/api/schedule/1").header("Authorization","Bearer s1")).andExpect(status().isOk());
  assertEquals(0,db.queryForObject("SELECT count(*) FROM schedule",Integer.class));
  mvc.perform(post("/api/schedule").header("Authorization","Bearer s1").contentType("application/json").content(body.replace("[1,2]","[1]").replace("lecture","exam"))).andExpect(status().isOk());
  assertEquals("exam",db.queryForObject("SELECT kind FROM schedule",String.class));
 }
}
