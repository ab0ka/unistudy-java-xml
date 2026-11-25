package kz.unistudy;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import com.fasterxml.jackson.databind.ObjectMapper;
class Merge57Test {
 @Test void mergePreservesGenuinePairsFillsOnlyMissingAndNeverRefillsClearedGrades() throws Exception {
  var path=Files.createTempDirectory("merge57").resolve("old.db");
  var env=new MockEnvironment().withProperty("UNISTUDY_DB",path.toString());
  var db=new JdbcTemplate(new Application().dataSource(env));
  db.update("DELETE FROM schema_migrations WHERE version=57");
  if(!db.queryForList("SELECT name FROM sqlite_master WHERE name='grade_merge_archive'").isEmpty())db.update("DELETE FROM grade_merge_archive WHERE version=57");
  db.update("INSERT INTO groups(id,name) VALUES(1,'G')");
  for(int id=1;id<=3;id++)db.update("INSERT INTO users(id,full_name,username,password_hash,role,active,approved,must_change_password,auth_version,group_id) VALUES(?,?,?,'unused','student',1,1,0,0,1)",id,"Student"+id,"merge"+id);
  db.update("INSERT INTO subjects(id,code,name,teacher_name,description,group_ids,teacher_ids) VALUES(1,'M','Merge','','','[1]','[]')");
  for(int id:new int[]{1,2,10,20,21})db.update("INSERT INTO assessments(id,subject_id,group_id,title,date,week,kind,version,is_demo,source) VALUES(?,1,1,'Work','2026-11-24',?,?,1,?,?)",id,id<20?1:2,id<20?"Зертханалық":"lecture",id<10?0:1,id<10?"manual":"demo54");
  db.update("INSERT INTO grade_entries(assessment_id,student_id,value,is_demo,source) VALUES(1,1,'0',0,'manual'),(1,2,'NP',0,'manual'),(2,1,'100',0,'manual'),(10,1,'90',1,'demo54'),(10,2,'80',1,'demo54'),(10,3,'70',1,'demo54'),(20,1,'85',1,'demo54'),(20,2,'75',1,'demo54'),(21,1,'95',1,'manual')");
  db=new JdbcTemplate(new Application().dataSource(env));
  assertEquals("0",db.queryForObject("SELECT value FROM grade_entries WHERE assessment_id=1 AND student_id=1",String.class));
  assertEquals("NP",db.queryForObject("SELECT value FROM grade_entries WHERE assessment_id=1 AND student_id=2",String.class));
  assertEquals("100",db.queryForObject("SELECT value FROM grade_entries WHERE assessment_id=2 AND student_id=1",String.class));
  assertEquals("70",db.queryForObject("SELECT value FROM grade_entries WHERE assessment_id=1 AND student_id=3",String.class));
  assertEquals(1,db.queryForObject("SELECT generated_origin FROM grade_entries WHERE assessment_id=1 AND student_id=3",Integer.class));
  assertEquals(1,db.queryForObject("SELECT archived FROM assessments WHERE id=10",Integer.class));
  assertEquals(0,db.queryForObject("SELECT archived FROM assessments WHERE id=2",Integer.class));
  assertEquals("90",db.queryForObject("SELECT value FROM grade_entries WHERE assessment_id=10 AND student_id=1",String.class));
  assertEquals("95",db.queryForObject("SELECT value FROM grade_entries WHERE assessment_id=20 AND student_id=1",String.class));
  assertEquals(0,db.queryForObject("SELECT count(*) FROM grade_entries WHERE is_demo<>0",Integer.class));
  assertEquals(0,db.queryForObject("SELECT count(*) FROM assessments WHERE is_demo<>0",Integer.class));
  assertEquals(2,db.queryForObject("SELECT count(*) FROM grade_entries g JOIN assessments a ON a.id=g.assessment_id WHERE a.week=1 AND a.archived=0 AND g.student_id=1",Integer.class));
  var snapshot=new ObjectMapper().readTree(db.queryForObject("SELECT data FROM grade_merge_archive WHERE version=57",String.class));
  assertEquals(9,snapshot.path("grade_entries").size());
  db.update("DELETE FROM grade_entries WHERE assessment_id=1 AND student_id=3");
  db=new JdbcTemplate(new Application().dataSource(env));
  assertEquals(0,db.queryForObject("SELECT count(*) FROM grade_entries WHERE assessment_id=1 AND student_id=3",Integer.class));
  assertEquals(1,db.queryForObject("SELECT count(*) FROM grade_merge_archive WHERE version=57",Integer.class));
  assertEquals(1,db.queryForObject("SELECT count(*) FROM schema_migrations WHERE version=57",Integer.class));
  assertTrue(db.queryForList("PRAGMA foreign_key_check").isEmpty());
 }
}
