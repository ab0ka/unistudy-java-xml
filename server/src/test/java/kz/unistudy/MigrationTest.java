package kz.unistudy;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.env.MockEnvironment;
import org.sqlite.SQLiteDataSource;

class MigrationTest {
  @Test void migrationKeepsOldGradesAndIdsAndNormalizesOnlySections() throws Exception {
    var path = Files.createTempDirectory("unistudy-migration").resolve("old.db");
    var legacy = new SQLiteDataSource();
    legacy.setUrl("jdbc:sqlite:"+path);
    new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(legacy);
    var db = new JdbcTemplate(legacy);
    db.update("INSERT INTO groups(id,name) VALUES(1,'Legacy')");
    db.update("INSERT INTO users(id,full_name,username,password_hash,role,group_id,active,approved,must_change_password,auth_version) VALUES(1,'Legacy','legacy','preserve-this','student',1,1,1,0,0)");
    db.update("INSERT INTO subjects(id,code,name,teacher_name,description,group_ids,teacher_ids) VALUES(1,'OLD','Legacy','Teacher','','[1]','[]')");
    db.update("INSERT INTO materials(id,subject_id,title,description,week,kind,section,group_ids,published,created_at) VALUES(1,1,'Legacy assignment','Original text',NULL,'lab','resource','[]',1,'original')");
    db.update("INSERT INTO assessments(id,subject_id,group_id,title,date,week,kind,version) VALUES(1,1,1,'Old title','2026-11-24',1,'exam',7)");
    db.update("INSERT INTO grade_entries(assessment_id,student_id,value) VALUES(1,1,'93')");
    var env = new MockEnvironment().withProperty("UNISTUDY_DB",path.toString());
    for(int run=0;run<2;run++) {
      var migrated = new JdbcTemplate(new Application().dataSource(env));
      assertEquals("93",migrated.queryForObject("SELECT value FROM grade_entries WHERE assessment_id=1 AND student_id=1",String.class));
      assertEquals(0,migrated.queryForObject("SELECT is_demo FROM grade_entries WHERE assessment_id=1",Integer.class));
      assertEquals("manual",migrated.queryForObject("SELECT source FROM assessments WHERE id=1",String.class));
      assertEquals(7,migrated.queryForObject("SELECT version FROM assessments WHERE id=1",Integer.class));
      assertNull(migrated.queryForObject("SELECT material_id FROM assessments WHERE id=1",Integer.class));
      assertEquals("preserve-this",migrated.queryForObject("SELECT password_hash FROM users WHERE id=1",String.class));
      assertEquals("assignment",migrated.queryForObject("SELECT section FROM materials WHERE id=1",String.class));
      assertEquals("Original text",migrated.queryForObject("SELECT description FROM materials WHERE id=1",String.class));
      assertTrue(migrated.queryForList("PRAGMA foreign_key_check").isEmpty());
    }
  }
}
