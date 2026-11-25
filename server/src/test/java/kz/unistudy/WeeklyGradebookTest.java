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
import com.fasterxml.jackson.databind.ObjectMapper;
@SpringBootTest @AutoConfigureMockMvc
class WeeklyGradebookTest {
 @DynamicPropertySource static void isolate(DynamicPropertyRegistry p) throws Exception {var r=Files.createTempDirectory("weekly55");p.add("UNISTUDY_DB",()->r.resolve("test.db").toString());p.add("UNISTUDY_STORAGE",()->r.resolve("files").toString());}
 @Autowired MockMvc mvc; @Autowired JdbcTemplate db; @Autowired ObjectMapper json;
 @Test void weeklySelectionIsIdempotentScopedAndKeepsAllWorksWithLegacyModeAliases() throws Exception {
  db.update("INSERT INTO groups(id,name) VALUES(1,'G1'),(2,'G2')");
  for(int id=1;id<=3;id++){db.update("INSERT INTO users(id,full_name,username,password_hash,role,active,approved,must_change_password,auth_version,group_id) VALUES(?,?,?,'unused',?,1,1,0,0,1)",id,"User"+id,"w"+id,id==1?"admin":id==2?"student":"teacher");db.update("INSERT INTO auth_sessions(user_id,auth_version,access_hash,refresh_hash,access_expires,refresh_expires) VALUES(?,0,?,?,?,?)",id,Store.digest("w"+id),Store.digest("wr"+id),Store.now()+300,Store.now()+300);}
  db.update("INSERT INTO subjects(id,code,name,teacher_name,description,group_ids,teacher_ids) VALUES(1,'W','Weekly','','','[1,2]','[3]'),(2,'F','Foreign','','','[1]','[]')");
  db.update("INSERT INTO materials(id,subject_id,title,description,week,kind,section,group_ids,published,created_at) VALUES(1,1,'Actual lab','',1,'lab','assignment','[1]',1,'now')");
  db.update("INSERT INTO assessments(id,subject_id,group_id,title,date,week,kind,version,is_demo,source) VALUES(10,1,1,'Empty old work','2026-11-24',1,'lab',1,1,'demo'),(11,1,1,'Graded old work','2026-11-24',1,'Зертханалық',4,1,'demo')");
  db.update("INSERT INTO grade_entries(assessment_id,student_id,value,is_demo,source) VALUES(11,2,'80',1,'demo')");
  String demo="{\"subject_id\":1,\"group_id\":1,\"kind\":\"lab\",\"week\":1,\"mode\":\"demo\"}";
  for(int n=0;n<2;n++)mvc.perform(post("/api/gradebook/weekly").header("Authorization","Bearer w3").contentType("application/json").content(demo)).andExpect(status().isOk()).andExpect(jsonPath("$.assessment.id").value(11)).andExpect(jsonPath("$.assessment.version").value(4)).andExpect(jsonPath("$.created").value(false)).andExpect(jsonPath("$.matching_assessments.length()").value(2)).andExpect(jsonPath("$.entries[0].value").value("80"));
  String real=demo.replace("demo","real");
  var created=mvc.perform(post("/api/gradebook/weekly").header("Authorization","Bearer w3").contentType("application/json").content(real)).andExpect(status().isOk()).andExpect(jsonPath("$.created").value(false)).andExpect(jsonPath("$.assessment.id").value(11)).andExpect(jsonPath("$.mode").value("unified")).andReturn();
  int assessment=json.readTree(created.getResponse().getContentAsByteArray()).path("assessment").path("id").asInt();
  mvc.perform(post("/api/gradebook/weekly").header("Authorization","Bearer w3").contentType("application/json").content(real)).andExpect(status().isOk()).andExpect(jsonPath("$.assessment.id").value(assessment)).andExpect(jsonPath("$.created").value(false));
  assertEquals(2,db.queryForObject("SELECT count(*) FROM assessments WHERE week=1",Integer.class));
  assertEquals("80",db.queryForObject("SELECT value FROM grade_entries WHERE assessment_id=11",String.class));
  mvc.perform(post("/api/gradebook/weekly").header("Authorization","Bearer w2").contentType("application/json").content(real)).andExpect(status().isForbidden());
  mvc.perform(post("/api/gradebook/weekly").header("Authorization","Bearer w3").contentType("application/json").content(real.replace("\"subject_id\":1","\"subject_id\":2"))).andExpect(status().isNotFound());
  for(int week:new int[]{0,16})mvc.perform(post("/api/gradebook/weekly").header("Authorization","Bearer w3").contentType("application/json").content(real.replace("\"week\":1","\"week\":"+week))).andExpect(status().isUnprocessableEntity());
  mvc.perform(post("/api/gradebook/weekly").header("Authorization","Bearer w3").contentType("application/json").content(real.replace("\"lab\"","\"exam\""))).andExpect(status().isUnprocessableEntity());
  db.update("INSERT INTO schedule(id,subject_id,teacher_id,group_id,date,start_time,end_time,room,room_key,kind,week,shared_key) VALUES(1,1,3,1,'2026-12-21','09:00','10:00','101','101','lecture',15,'shared'),(2,1,3,2,'2026-12-21','09:00','10:00','101','101','lecture',15,'shared')");
  mvc.perform(post("/api/gradebook/weekly").header("Authorization","Bearer w3").contentType("application/json").content("{\"subject_id\":1,\"group_id\":2,\"kind\":\"lecture\",\"week\":15,\"mode\":\"real\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.assessment.schedule_id").value(2));
  mvc.perform(post("/api/gradebook/weekly").header("Authorization","Bearer w3").contentType("application/json").content("{\"subject_id\":1,\"group_id\":1,\"kind\":\"sro\",\"week\":12,\"mode\":\"real\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.created").value(true)).andExpect(jsonPath("$.assessment.material_id").isEmpty()).andExpect(jsonPath("$.entries.length()").value(0));
  db.update("INSERT INTO materials(id,subject_id,title,description,week,kind,section,group_ids,published,created_at) VALUES(2,1,'Week14 lab','',14,'lab','assignment','[1]',1,'now')");
  mvc.perform(post("/api/gradebook/weekly").header("Authorization","Bearer w3").contentType("application/json").content("{\"subject_id\":1,\"group_id\":1,\"kind\":\"lab\",\"week\":14,\"mode\":\"demo\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.created").value(true)).andExpect(jsonPath("$.assessment.material_id").value(2)).andExpect(jsonPath("$.assessment.is_demo").value(0));
  var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
  var gate=new java.util.concurrent.CountDownLatch(1);
  java.util.concurrent.Callable<Integer> open=()->{
    gate.await();
    var response=mvc.perform(post("/api/gradebook/weekly").header("Authorization","Bearer w3").contentType("application/json").content("{\"subject_id\":1,\"group_id\":1,\"kind\":\"sro\",\"week\":13,\"mode\":\"real\"}")).andExpect(status().isOk()).andReturn();
    return json.readTree(response.getResponse().getContentAsByteArray()).path("assessment").path("id").asInt();
  };
  try {
    var first=pool.submit(open);var second=pool.submit(open);gate.countDown();
    assertEquals(first.get(20,java.util.concurrent.TimeUnit.SECONDS),second.get(20,java.util.concurrent.TimeUnit.SECONDS));
    assertEquals(1,db.queryForObject("SELECT count(*) FROM assessments WHERE week=13",Integer.class));
  } finally {pool.shutdownNow();}

 }
}
