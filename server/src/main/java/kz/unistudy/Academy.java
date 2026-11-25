package kz.unistudy;

import static kz.unistudy.Input.*;
import static kz.unistudy.Store.*;

import java.net.URI;
import java.time.Instant;
import java.util.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@Transactional
class Academy {
  final Store s;
  final Blobs blobs;

  Academy(Store s, Blobs blobs) {
    this.s = s;
    this.blobs = blobs;
  }

  Map<String, Object> group(Map<String, Object> g, boolean count) {
    return fields(
        "id",
        g.get("id"),
        "name",
        g.get("name"),
        "student_count",
        count
            ? s.db.queryForObject(
                "SELECT count(*) FROM users WHERE group_id=? AND active=1 AND approved=1 AND"
                    + " role='student'",
                Integer.class,
                g.get("id"))
            : 0);
  }

  @GetMapping("/groups")
  Object groups() {
    var u = s.optional();
    Set<Object> scope = u != null && role(u, "teacher") ? s.groupsOf(u) : Set.of();
    return s.rows("SELECT * FROM groups ORDER BY name").stream()
        .filter(
            g ->
                u == null
                    || role(u, "admin")
                    || scope.contains(g.get("id"))
                    || role(u, "student") && Objects.equals(u.get("group_id"), g.get("id")))
        .map(g -> group(g, u != null && !role(u, "student")))
        .toList();
  }

  @PostMapping("/groups")
  Object createGroup(@RequestBody Map<String, Object> b) {
    var u = s.staff();
    keys(b, "name");
    int id =
        s.insert(
            "groups",
            fields(
                "name",
                str(b, "name", 1, 120, true),
                "owner_id",
                role(u, "teacher") ? u.get("id") : null));
    return group(s.get("groups", id), true);
  }

  void groupScope(Map<String, Object> u, int id) {
    s.get("groups", id);
    if (!role(u, "admin") && !s.groupsOf(u).contains(id)) throw ApiError.missing();
  }

  @GetMapping("/groups/{id}/students")
  Object students(@PathVariable int id) {
    var u = s.staff();
    groupScope(u, id);
    return s
        .rows(
            "SELECT * FROM users WHERE role='student' AND group_id=?"
                + (role(u, "teacher") ? " AND active=1 AND approved=1" : "")
                + " ORDER BY full_name,id",
            id)
        .stream()
        .map(s::userJson)
        .toList();
  }

  @PostMapping("/groups/{id}/students")
  Object assign(@PathVariable int id, @RequestBody Map<String, Object> b) {
    var u = s.staff();
    groupScope(u, id);
    keys(b, "user_id");
    int uid = id(b.get("user_id"));
    var student = s.get("users", uid);
    if (!role(student, "student")
        || role(u, "teacher")
            && (!yes(student.get("approved"))
                || !yes(student.get("active"))
                || !s.groupsOf(u).contains(student.get("group_id")))) throw ApiError.missing();
    if (!Objects.equals(id, student.get("group_id"))) {
      s.update("users", uid, fields("group_id", id));
      s.invalidate(uid);
    }
    return s.userJson(s.get("users", uid));
  }

  @PostMapping("/groups/{id}/new-student")
  Object newStudent(@PathVariable int id, @RequestBody Map<String, Object> b) {
    var u = s.staff();
    if (!role(u, "teacher") && !role(u, "admin")) throw ApiError.denied();
    groupScope(u, id);
    keys(b, "full_name username password");
    var username = name(b);
    var fullName = str(b, "full_name", 1, 200, true);
    var password = pwd(b, "password", true);
    if (s.find("SELECT id FROM users WHERE username=?", username) != null)
      throw new ApiError(409, "Р‘Т±Р» Р»РѕРіРёРЅ Р±Т±СЂС‹РЅРЅР°РЅ С‚С–СЂРєРµР»РіРµРЅ.");
    int uid = s.insert("users", fields(
        "full_name", fullName, "username", username,
        "password_hash", Auth.hashPassword(password), "role", "student",
        "group_id", id, "active", true, "approved", true,
        "must_change_password", false, "auth_version", 0));
    return s.userJson(s.get("users", uid));
  }

  Map<String, Object> subjectJson(Map<String, Object> row) {
    var out = only(row, "id name code teacher_name description");
    for (var key : List.of("group_ids", "teacher_ids", "external_links"))
      out.put(key, s.list(row, key));
    return out;
  }

  @GetMapping("/subjects")
  Object subjects() {
    var u = s.user();
    return s.rows("SELECT * FROM subjects ORDER BY name,id").stream()
        .filter(row -> s.seesSubject(u, row))
        .map(this::subjectJson)
        .toList();
  }

  List<Map<String, Object>> links(Object value) {
    if (!(value instanceof List<?> l) || l.size() > 100) throw ApiError.invalid();
    var out = new ArrayList<Map<String, Object>>();
    for (var o : l) {
      if (!(o instanceof Map<?, ?> m)
          || m.size() != 2
          || !(m.get("title") instanceof String t)
          || t.isBlank()
          || t.length() > 400
          || !(m.get("url") instanceof String url)
          || url.length() > 2048) throw ApiError.invalid();
      try {
        var uri = URI.create(url);
        if (!"https".equalsIgnoreCase(uri.getScheme())
            || uri.getHost() == null
            || uri.getRawUserInfo() != null
            || uri.getPort() == 0
            || uri.getPort() > 65535
            || uri.getRawAuthority().endsWith(":")) throw ApiError.invalid();
      } catch (IllegalArgumentException e) {
        throw ApiError.invalid();
      }
      out.add(fields("title", t.strip(), "url", url));
    }
    return out;
  }

  Map<String, Object> subjectValues(
      Map<String, Object> b, Map<String, Object> u, Map<String, Object> old) {
    keys(b, "name code teacher_name description group_ids teacher_ids external_links");
    var v = fields();
    for (var k : List.of("name", "code", "teacher_name"))
      if (old == null || b.containsKey(k))
        v.put(k, str(b, k, 1, k.equals("code") ? 80 : k.equals("name") ? 300 : 200, true));
    if (b.containsKey("description")) v.put("description", str(b, "description", 0, 20000, false));
    else if (old == null) v.put("description", "");
    if (b.containsKey("external_links")) v.put("external_links", links(b.get("external_links")));
    for (var k : List.of("group_ids", "teacher_ids")) {
      if (!b.containsKey(k)) {
        if (old == null) v.put(k, List.of());
        continue;
      }
      var ids = ids(b.get(k));
      if (k.equals("group_ids")) {
        if (role(u, "teacher") && !s.groupsOf(u).containsAll(ids)) throw ApiError.denied();
        ids.forEach(id -> s.get("groups", id));
      } else if (role(u, "admin")) {
        for (var id : ids) {
          var t = s.get("users", id);
          if (!role(t, "teacher") || !yes(t.get("active")) || !yes(t.get("approved")))
            throw ApiError.invalid();
        }
      } else {
        if (!new HashSet<>(ids).equals(new HashSet<>(s.list(old, k)))) throw ApiError.denied();
        continue;
      }
      v.put(k, ids);
    }
    return v;
  }

  @PostMapping("/subjects")
  Object createSubject(@RequestBody Map<String, Object> b) {
    var u = s.admin();
    return subjectJson(s.get("subjects", s.insert("subjects", subjectValues(b, u, null))));
  }

  @PatchMapping("/subjects/{id}")
  Object editSubject(@PathVariable int id, @RequestBody Map<String, Object> b) {
    var u = s.staff();
    var old = s.subject(u, id);
    s.update("subjects", id, subjectValues(b, u, old));
    return subjectJson(s.get("subjects", id));
  }

  Map<String, Object> materialJson(Map<String, Object> m) {
    var out = only(m, "id subject_id week kind title description created_at deadline section");
    Object deadline = m.get("deadline");
    if (deadline != null && !deadline.toString().isBlank())
      out.put("deadline", SubmissionDeadline.instant(deadline.toString()).toString());
    var now = Instant.now();
    boolean closed = "assignment".equals(m.get("section")) && SubmissionDeadline.closed(m, now);
    out.put("server_now", now.toString());
    out.put("submission_closed", closed);
    var user = s.user();
    String status = null;
    if (role(user, "student") && "assignment".equals(m.get("section"))) {
      boolean graded = s.find("SELECT g.value FROM grade_entries g JOIN assessments a ON a.id=g.assessment_id WHERE a.material_id=? AND a.group_id=? AND g.student_id=? AND g.is_demo=0 AND g.generated_origin=0 AND a.archived=0 AND g.value<>'NP' LIMIT 1",
          m.get("id"), user.get("group_id"), user.get("id")) != null;
      boolean submitted = s.find("SELECT t.id FROM submissions t WHERE t.material_id=? AND t.student_id=? AND (length(trim(t.text))>0 OR EXISTS(SELECT 1 FROM submission_files f WHERE f.submission_id=t.id))",
          m.get("id"), user.get("id")) != null;
      status = graded ? "graded" : submitted ? "submitted" : closed ? "overdue" : "not_submitted";
    }
    out.put("student_status", status);
    out.put("published", yes(m.get("published")));
    out.put("group_ids", s.list(m, "group_ids"));
    out.put(
        "files",
        s.rows(
            "SELECT id,name,size,mime FROM attachments WHERE material_id=? ORDER BY id",
            m.get("id")));
    return out;
  }

  @GetMapping("/materials/{id}")
  Object material(@PathVariable int id) {
    return materialJson(s.material(s.user(), id));
  }

  @GetMapping("/subjects/{id}/materials")
  Object materials(@PathVariable int id) {
    var u = s.user();
    s.subject(u, id);
    return s.rows("SELECT * FROM materials WHERE subject_id=? ORDER BY week,id", id).stream()
        .filter(m -> s.seesMaterial(u, m))
        .map(this::materialJson)
        .toList();
  }

  static String materialKind(Object value) {
    return choice(value, "lecture", "lab", "sro", "note", "guide", "exam");
  }

  @GetMapping("/materials")
  Object searchMaterials(
      @RequestParam(defaultValue = "") String q,
      @RequestParam(name = "subject_id", required = false) String subjectId,
      @RequestParam(required = false) String week,
      @RequestParam(required = false) String kind,
      @RequestParam(required = false) String section) {
    var user = s.user();
    var sql = new StringBuilder("SELECT m.*, sub.name AS subject_name, sub.teacher_ids,"
        + " sub.group_ids AS subject_groups FROM materials m JOIN subjects sub ON sub.id=m.subject_id WHERE 1=1");
    var args = new ArrayList<Object>();
    if (subjectId != null) { sql.append(" AND m.subject_id=?"); args.add(id(subjectId)); }
    if ("general".equals(week)) sql.append(" AND m.week IS NULL");
    else if (week != null) { sql.append(" AND m.week=?"); args.add(num(week, 1, 15)); }
    if (kind != null) { sql.append(" AND m.kind=?"); args.add(materialKind(kind)); }
    if (section != null) { sql.append(" AND m.section=?"); args.add(choice(section, "assignment", "resource")); }
    sql.append(" ORDER BY sub.name,m.week,m.id");
    String query = fold(q.strip());
    var results = new ArrayList<Map<String, Object>>();
    for (var m : s.rows(sql.toString(), args.toArray())) {
      var subject = fields("group_ids", m.get("subject_groups"), "teacher_ids", m.get("teacher_ids"));
      if (!s.seesSubject(user, subject) || !s.seesMaterial(user, m)) continue;
      var row = materialJson(m);
      if (!query.isEmpty() && !fold((String)m.get("title")).contains(query)
          && !fold((String)m.get("description")).contains(query)
          && ((List<?>)row.get("files")).stream().noneMatch(file ->
              fold((String)((Map<?, ?>)file).get("name")).contains(query))) continue;
      row.put("subject_name", m.get("subject_name"));
      results.add(row);
    }
    return results;
  }

  Map<String, Object> materialValues(
      Map<String, Object> b, Map<String, Object> u, Map<String, Object> old) {
    keys(b, "subject_id week kind section title description published group_ids deadline");
    var v =
        old == null
            ? fields(
                "description",
                "",
                "published",
                false,
                "section",
                "resource",
                "group_ids",
                List.of(),
                "created_at",
                Instant.now().toString())
            : fields();
    if (old == null || b.containsKey("subject_id")) v.put("subject_id", id(b.get("subject_id")));
    if (old == null || b.containsKey("title")) v.put("title", str(b, "title", 1, 400, true));
    if (old == null || b.containsKey("kind"))
      v.put("kind", materialKind(b.get("kind")));
    if (b.containsKey("section"))
      v.put("section", choice(b.get("section"), "assignment", "resource"));
    if (b.containsKey("description")) v.put("description", str(b, "description", 0, 20000, false));
    if (b.containsKey("group_ids")) v.put("group_ids", ids(b.get("group_ids")));
    if (b.containsKey("published")) v.put("published", bool(b.get("published")));
    if (b.containsKey("week"))
      v.put("week", b.get("week") == null ? null : num(b.get("week"), 1, 15));
    if (b.containsKey("deadline"))
      v.put("deadline", b.get("deadline") == null ? null : deadline(b.get("deadline")));
    String kind = (String) v.getOrDefault("kind", old == null ? null : old.get("kind"));
    v.put("section", Set.of("lab", "sro").contains(kind) ? "assignment" : "resource");
    var merged = fields();
    if (old != null) merged.putAll(old);
    merged.putAll(v);
    var sub = s.subject(u, merged.get("subject_id"));
    if ((v.containsKey("subject_id") || v.containsKey("group_ids"))
        && !s.list(sub, "group_ids").containsAll(s.list(merged, "group_ids")))
      throw ApiError.invalid();
    return v;
  }

  @PostMapping("/materials")
  Object addMaterial(@RequestBody Map<String, Object> b) {
    var u = s.staff();
    return materialJson(s.get("materials", s.insert("materials", materialValues(b, u, null))));
  }

  @PatchMapping("/materials/{id}")
  Object editMaterial(@PathVariable int id, @RequestBody Map<String, Object> b) {
    var u = s.staff();
    var old = s.material(u, id);
    s.update("materials", id, materialValues(b, u, old));
    return materialJson(s.get("materials", id));
  }

  @DeleteMapping("/materials/{id}")
  Object deleteMaterial(@PathVariable int id) {
    s.material(s.staff(), id);
    var keys =
        s.rows(
            "SELECT key FROM attachments WHERE material_id=? UNION ALL SELECT f.key FROM"
                + " submission_files f JOIN submissions t ON t.id=f.submission_id WHERE"
                + " t.material_id=?",
            id,
            id);
    s.db.update("DELETE FROM materials WHERE id=?", id);
    keys.forEach(row -> blobs.afterDelete((String) row.get("key")));
    return OK;
  }
}
