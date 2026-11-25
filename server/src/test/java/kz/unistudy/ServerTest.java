package kz.unistudy;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class ServerTest {
  // Synthetic Argon2id fixture produced by argon2-cffi with its default settings.
  static final String HASH =
      "$argon2id$v=19$m=65536,t=3,p=4$ss9k2GzhyktjbUZWjPGtAg$tHEmrH48pSSYbEZzCi6tHWFnaQ4L2YfXQ3rry6fRlj8";

  @DynamicPropertySource
  static void isolate(DynamicPropertyRegistry properties) throws Exception {
    var root = Files.createTempDirectory("unistudy-server-test");
    properties.add("UNISTUDY_DB", () -> root.resolve("test.db").toString());
    properties.add("UNISTUDY_STORAGE", () -> root.resolve("files").toString());
  }

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate db;
  @Autowired ObjectMapper json;

  @Test
  void adminCreatesApprovedAccountsAndRestrictsRoles() throws Exception {
    for (int id : List.of(9810, 9811)) {
      db.update("INSERT INTO users(id,full_name,username,password_hash,role,active,approved,must_change_password,auth_version) VALUES(?,?,?,?,?,1,1,0,0)",
          id, "Account fixture", "account" + id, HASH, id == 9810 ? "admin" : "teacher");
      db.update("INSERT INTO auth_sessions(user_id,auth_version,access_hash,refresh_hash,access_expires,refresh_expires) VALUES(?,0,?,?,?,?)",
          id, Store.digest("account" + id), Store.digest("account-refresh" + id), Store.now()+300, Store.now()+300);
    }
    db.update("INSERT INTO groups(id,name) VALUES(9810,'Account fixture group')");
    var body = Store.fields("full_name", "New teacher", "username", "new-teacher", "password", "Example123", "role", "teacher", "group_id", null);
    mvc.perform(post("/api/admin/users").header("Authorization", "Bearer account9811").contentType("application/json").content(json.writeValueAsBytes(body)))
        .andExpect(status().isForbidden());
    var created = mvc.perform(post("/api/admin/users").header("Authorization", "Bearer account9810").contentType("application/json").content(json.writeValueAsBytes(body)))
        .andExpect(status().isOk()).andExpect(jsonPath("$.approved").value(true)).andExpect(jsonPath("$.must_change_password").value(false)).andReturn();
    assertFalse(created.getResponse().getContentAsString().contains("password_hash"));
    mvc.perform(post("/api/auth/login").contentType("application/json").content("{\"username\":\"new-teacher\",\"password\":\"Example123\"}"))
        .andExpect(status().isOk());
    mvc.perform(post("/api/admin/users").header("Authorization", "Bearer account9810").contentType("application/json").content(json.writeValueAsBytes(body)))
        .andExpect(status().isConflict());
    body.put("username", "new-student"); body.put("role", "student"); body.put("group_id", 9810);
    mvc.perform(post("/api/admin/users").header("Authorization", "Bearer account9810").contentType("application/json").content(json.writeValueAsBytes(body)))
        .andExpect(status().isOk()).andExpect(jsonPath("$.group_id").value(9810));
    body.put("username", "illegal-admin"); body.put("role", "admin");
    mvc.perform(post("/api/admin/users").header("Authorization", "Bearer account9810").contentType("application/json").content(json.writeValueAsBytes(body)))
        .andExpect(status().isUnprocessableEntity());
  }

  @Test
  void pythonHashAndConcurrentRotation() throws Exception {
    db.update(
        "INSERT INTO"
            + " users(full_name,username,password_hash,role,active,approved,must_change_password,auth_version)"
            + " VALUES('Fixture','strasse',?,'student',1,1,0,0)",
        HASH);
    var result =
        mvc.perform(
                post("/api/auth/login")
                    .contentType("application/json")
                    .content(
                        json.writeValueAsBytes(
                            Map.of("username", "Straße", "password", "test-only-password"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.user.must_change_password").value(false))
            .andReturn();
    var tokens = json.readTree(result.getResponse().getContentAsByteArray());
    var oldAccess = tokens.get("access_token").asText();
    mvc.perform(get("/api/subjects").header("Authorization", "Bearer " + oldAccess))
        .andExpect(status().isOk());
    mvc.perform(get("/api/admin/users").header("Authorization", "Bearer " + oldAccess))
        .andExpect(status().isForbidden());
    var body =
        json.writeValueAsBytes(Map.of("refresh_token", tokens.get("refresh_token").asText()));
    var gate = new CountDownLatch(1);
    Callable<Integer> refresh =
        () -> {
          gate.await();
          return mvc.perform(
                  post("/api/auth/refresh").contentType("application/json").content(body))
              .andReturn()
              .getResponse()
              .getStatus();
        };
    var pool = Executors.newFixedThreadPool(2);
    try {
      var first = pool.submit(refresh);
      var second = pool.submit(refresh);
      gate.countDown();
      var statuses =
          new ArrayList<>(
              List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)));
      Collections.sort(statuses);
      assertEquals(List.of(200, 401), statuses);
    } finally {
      pool.shutdownNow();
    }
    mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + oldAccess))
        .andExpect(status().isUnauthorized());
    assertEquals(
        HASH,
        db.queryForObject(
            "SELECT password_hash FROM users WHERE username='strasse'", String.class));
  }

  @Test
  void duplicateGroupReturnsConflictAndRollsBack() throws Exception {
    db.update(
        "INSERT INTO"
            + " users(full_name,username,password_hash,role,active,approved,must_change_password,auth_version)"
            + " VALUES('Fixture Admin','test-admin',?,'admin',1,1,0,0)",
        HASH);
    var login =
        mvc.perform(
                post("/api/auth/login")
                    .contentType("application/json")
                    .content(
                        json.writeValueAsBytes(
                            Map.of("username", "test-admin", "password", "test-only-password"))))
            .andExpect(status().isOk())
            .andReturn();
    var token =
        json.readTree(login.getResponse().getContentAsByteArray()).get("access_token").asText();
    var body = json.writeValueAsBytes(Map.of("name", "Fixture group"));
    for (var expected : List.of(200, 409)) {
      mvc.perform(
              post("/api/groups")
                  .contentType("application/json")
                  .header("Authorization", "Bearer " + token)
                  .content(body))
          .andExpect(status().is(expected));
    }
    assertEquals(
        1,
        db.queryForObject("SELECT count(*) FROM groups WHERE name='Fixture group'", Integer.class));
  }

  @Test
  void sharedRoomUsesPythonCasefoldForConflicts() throws Exception {
    db.update("INSERT INTO groups(id,name) VALUES(8001,'Group 1'),(8002,'Group 2')");
    for (int id : List.of(8101, 8102, 8103)) {
      db.update(
          "INSERT INTO"
              + " users(id,full_name,username,password_hash,role,active,approved,must_change_password,auth_version)"
              + " VALUES(?,?,?,?,?,1,1,0,0)",
          id,
          "Staff " + id,
          "staff" + id,
          HASH,
          id == 8103 ? "admin" : "teacher");
    }
    db.update(
        "INSERT INTO subjects(id,code,name,teacher_name,description,group_ids,teacher_ids)"
            + " VALUES(8201,'T','A','T','','[8001,8002]','[8101,8102]')");
    db.update(
        "INSERT INTO"
            + " auth_sessions(user_id,auth_version,access_hash,refresh_hash,access_expires,refresh_expires)"
            + " VALUES(8103,0,?,?,?,?)",
        Store.digest("test-schedule"),
        Store.digest("test-schedule-refresh"),
        Store.now() + 300,
        Store.now() + 300);
    var lesson =
        Store.fields(
            "subject_id",
            8201,
            "teacher_id",
            8101,
            "group_id",
            8001,
            "date",
            "2026-11-23",
            "start_time",
            "09:00",
            "end_time",
            "10:00",
            "room",
            "STRASSE 1",
            "kind",
            "lecture",
            "week",
            1);
    for (int expected : List.of(200, 409)) {
      mvc.perform(
              post("/api/schedule")
                  .header("Authorization", "Bearer test-schedule")
                  .contentType("application/json")
                  .content(json.writeValueAsBytes(lesson)))
          .andExpect(status().is(expected));
      lesson.put("teacher_id", 8102);
      lesson.put("group_id", 8002);
      lesson.put("room", "Straße  1");
    }
    assertEquals(
        1, db.queryForObject("SELECT count(*) FROM schedule WHERE subject_id=8201", Integer.class));
  }

  @Test
  void globalMaterialSearchChecksSubjectPublicationGroupAndFileName() throws Exception {
    db.update("INSERT INTO groups(id,name) VALUES(9100,'Search A'),(9101,'Search B')");
    for (int id : List.of(9110, 9111)) {
      db.update("INSERT INTO users(id,full_name,username,password_hash,role,group_id,active,approved,must_change_password,auth_version)"
          + " VALUES(?,?,?,?,?,9100,1,1,0,0)", id, "Search User", "search" + id, HASH,
          id == 9110 ? "teacher" : "student");
      db.update("INSERT INTO auth_sessions(user_id,auth_version,access_hash,refresh_hash,access_expires,refresh_expires)"
          + " VALUES(?,0,?,?,?,?)", id, Store.digest("search" + id), Store.digest("refresh" + id),
          Store.now() + 300, Store.now() + 300);
    }
    db.update("INSERT INTO subjects(id,code,name,teacher_name,description,group_ids,teacher_ids) VALUES"
        + "(9120,'SEARCH-A','Search A','Teacher','','[9100,9101]','[9110]'),"
        + "(9121,'SEARCH-B','Search B','Other','','[9101]','[]')");
    String teacher = "Bearer search9110", student = "Bearer search9111";
    for (String kind : List.of("note","guide","exam")) {
      var material = Store.fields("subject_id",9120,"title","Review "+kind,"kind",kind,
          "description","Емтихан","week",null,"published",true);
      mvc.perform(post("/api/materials").header("Authorization", teacher)
              .contentType("application/json").content(json.writeValueAsBytes(material)))
          .andExpect(status().isOk()).andExpect(jsonPath("$.published").value(true));
    }
    db.update("INSERT INTO attachments(material_id,name,key,size,mime)"
        + " SELECT id,'Үлгі-Straße.PDF','search-test-key',7,'application/pdf' FROM materials"
        + " WHERE subject_id=9120 AND kind='guide'");
    db.update("INSERT INTO materials(subject_id,title,description,week,kind,group_ids,published,created_at)"
        + " VALUES(9120,'Review draft','',1,'note','[]',0,'now'),"
        + "(9120,'Review restricted','',1,'note','[9101]',1,'now'),"
        + "(9121,'Review hidden subject','',1,'note','[]',1,'now')");
    for (String q : List.of("rEviEw", "ЕМТИХАН")) {
      mvc.perform(get("/api/materials").param("q",q).header("Authorization",student))
          .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3));
    }
    mvc.perform(get("/api/materials").header("Authorization",teacher).param("q","review"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(5));
    mvc.perform(get("/api/materials").header("Authorization",student).param("q","ҮЛГІ-STRASSE")
            .param("subject_id","9120").param("week","general").param("kind","guide").param("section","resource"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].subject_name").value("Search A"))
        .andExpect(jsonPath("$[0].files[0].name").value("Үлгі-Straße.PDF"));
    mvc.perform(get("/api/materials").header("Authorization",student).param("subject_id","9120").param("week","1"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    mvc.perform(get("/api/subjects/9120/materials").header("Authorization",student))
        .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3));
    mvc.perform(patch("/api/auth/profile").header("Authorization",student).contentType("application/json")
            .content(json.writeValueAsBytes(Map.of("full_name","Updated Name","current_password","test-only-password"))))
        .andExpect(status().isOk()).andExpect(jsonPath("$.full_name").value("Updated Name"));
    for(String restricted : List.of("role","group_id")) {
      mvc.perform(patch("/api/auth/profile").header("Authorization",student).contentType("application/json")
              .content(json.writeValueAsBytes(Map.of(restricted,"admin","current_password","test-only-password"))))
          .andExpect(status().isUnprocessableEntity());
    }
  }

  @Test
  void subjectLinksUseAndroidCompatibleHttpsUrls() {
    var academy = new Academy(null, null);
    for (var url :
        List.of(
            "http://example.com/",
            "https://user@example.com/",
            "https://example.com:0/",
            "https://example.com:70000/",
            "https://example.com:/",
            "https:///missing-host")) {
      assertThrows(
          ApiError.class, () -> academy.links(List.of(Map.of("title", "Resource", "url", url))));
    }
    var links = List.of(Map.of("title", "Resource", "url", "https://example.com:443/"));
    assertEquals(links, academy.links(links));
  }

  @Test
  void staffCreatesOnlyStudentsInAllowedGroups() throws Exception {
    for (int uid : List.of(9301, 9302, 9303)) {
      db.update("INSERT INTO users(id,full_name,username,password_hash,role,active,approved,must_change_password,auth_version) VALUES(?,?,?,?,?,1,1,0,0)",
          uid, "Creator " + uid, "creator" + uid, HASH,
          uid == 9301 ? "teacher" : uid == 9302 ? "student" : "admin");
      db.update("INSERT INTO auth_sessions(user_id,auth_version,access_hash,refresh_hash,access_expires,refresh_expires) VALUES(?,0,?,?,?,?)",
          uid, Store.digest("creator" + uid), Store.digest("creator-refresh" + uid), Store.now() + 300, Store.now() + 300);
    }
    db.update("INSERT INTO groups(id,name,owner_id) VALUES(9311,'Owned create group',9301),(9312,'Other create group',NULL)");
    var body = new LinkedHashMap<String, Object>(Map.of("full_name", " New Student ", "username", " New-Straße ", "password", "new-test-password"));
    for (var attempt : List.of(new int[]{9302, 9311, 403}, new int[]{9301, 9312, 404})) {
      mvc.perform(post("/api/groups/" + attempt[1] + "/new-student")
              .header("Authorization", "Bearer creator" + attempt[0]).contentType("application/json")
              .content(json.writeValueAsBytes(body)))
          .andExpect(status().is(attempt[2]));
    }
    for (var forbidden : List.of("role", "group_id", "approved", "active", "must_change_password")) {
      var malicious = new LinkedHashMap<>(body);
      malicious.put(forbidden, "admin");
      mvc.perform(post("/api/groups/9311/new-student").header("Authorization", "Bearer creator9301")
              .contentType("application/json").content(json.writeValueAsBytes(malicious)))
          .andExpect(status().isUnprocessableEntity());
    }
    for (var invalid : List.of(Map.of("password", "short"), Map.of("full_name", "   "), Map.of("username", "ab"))) {
      var bad = new LinkedHashMap<>(body);
      bad.putAll(invalid);
      mvc.perform(post("/api/groups/9311/new-student").header("Authorization", "Bearer creator9301")
              .contentType("application/json").content(json.writeValueAsBytes(bad)))
          .andExpect(status().isUnprocessableEntity());
    }
    mvc.perform(post("/api/groups/9311/new-student").header("Authorization", "Bearer creator9301")
            .contentType("application/json").content(json.writeValueAsBytes(body)))
        .andExpect(status().isOk()).andExpect(jsonPath("$.username").value("new-strasse"))
        .andExpect(jsonPath("$.full_name").value("New Student"))
        .andExpect(jsonPath("$.role").value("student")).andExpect(jsonPath("$.group_id").value(9311))
        .andExpect(jsonPath("$.active").value(true)).andExpect(jsonPath("$.approved").value(true))
        .andExpect(jsonPath("$.must_change_password").value(false)).andExpect(jsonPath("$.password_hash").doesNotExist());
    mvc.perform(post("/api/auth/login").contentType("application/json")
            .content(json.writeValueAsBytes(Map.of("username", "new-strasse", "password", "new-test-password"))))
        .andExpect(status().isOk());
    assertTrue(db.queryForObject("SELECT password_hash FROM users WHERE username='new-strasse'", String.class).startsWith("$argon2id$"));
    mvc.perform(post("/api/groups/9311/new-student").header("Authorization", "Bearer creator9301")
            .contentType("application/json").content(json.writeValueAsBytes(body)))
        .andExpect(status().isConflict());
    body.put("username", "admin-created-student");
    mvc.perform(post("/api/groups/9312/new-student").header("Authorization", "Bearer creator9303")
            .contentType("application/json").content(json.writeValueAsBytes(body)))
        .andExpect(status().isOk()).andExpect(jsonPath("$.group_id").value(9312))
        .andExpect(jsonPath("$.role").value("student"));
    assertEquals(2, db.queryForObject("SELECT count(*) FROM users WHERE group_id IN (9311,9312)", Integer.class));
  }

  @Test
  void responsesHaveBrowserSecurityHeaders() throws Exception {
    mvc.perform(get("/api/health"))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"))
        .andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.containsString("frame-ancestors 'none'")));
  }
}
