package kz.unistudy;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
class Store {
  final JdbcTemplate db;
  final ObjectMapper json;
  final HttpServletRequest request;

  Store(JdbcTemplate db, ObjectMapper json, HttpServletRequest request) {
    this.db = db;
    this.json = json;
    this.request = request;
  }

  static final Map<String, Object> OK = Map.of("ok", true);

  static Map<String, Object> fields(Object... pairs) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) out.put((String) pairs[i], pairs[i + 1]);
    return out;
  }

  static Map<String, Object> only(Map<String, Object> row, String keys) {
    var out = new LinkedHashMap<String, Object>();
    for (String key : keys.split(" ")) out.put(key, row.get(key));
    return out;
  }

  List<Map<String, Object>> rows(String sql, Object... params) {
    return db.queryForList(sql, params);
  }

  Map<String, Object> find(String sql, Object... params) {
    var rows = rows(sql, params);
    return rows.isEmpty() ? null : rows.get(0);
  }

  Map<String, Object> get(String table, Object id) {
    var row = find("SELECT * FROM " + table + " WHERE id=?", id);
    if (row == null) throw ApiError.missing();
    return row;
  }

  String encode(Object v) {
    try {
      return json.writeValueAsString(v);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  List<?> list(Map<String, Object> row, String key) {
    var v = row.get(key);
    if (v instanceof List<?> l) return l;
    try {
      return json.readValue((String) v, List.class);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  Object bind(Object v) {
    return v instanceof List<?> ? encode(v) : v;
  }

  int insert(String table, Map<String, Object> values) {
    var columns = String.join(",", values.keySet());
    var marks = String.join(",", Collections.nCopies(values.size(), "?"));
    db.update(
        "INSERT INTO " + table + " (" + columns + ") VALUES (" + marks + ")",
        values.values().stream().map(this::bind).toArray());
    return db.queryForObject("SELECT last_insert_rowid()", Integer.class);
  }

  void update(String table, Object id, Map<String, Object> values) {
    if (values.isEmpty()) return;
    var assigns = values.keySet().stream().map(k -> k + "=?").toList();
    var params = new ArrayList<>(values.values().stream().map(this::bind).toList());
    params.add(id);
    db.update(
        "UPDATE " + table + " SET " + String.join(",", assigns) + " WHERE id=?", params.toArray());
  }

  static long now() {
    return Instant.now().getEpochSecond();
  }

  static String digest(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  static boolean yes(Object v) {
    return Boolean.TRUE.equals(v) || v instanceof Number n && n.intValue() != 0;
  }

  static boolean role(Map<String, Object> u, String r) {
    return r.equals(u.get("role"));
  }

  void active(Map<String, Object> u) {
    if (!yes(u.get("active"))) throw new ApiError(403, "Аккаунт өшірілген.");
    if (!yes(u.get("approved"))) throw new ApiError(403, "Аккаунт әкімшінің мақұлдауын күтуде.");
  }

  Map<String, Object> optional() {
    String h = request.getHeader("Authorization");
    if (h == null) return null;
    if (!h.regionMatches(true, 0, "Bearer ", 0, 7) || h.length() < 8 || h.length() > 167)
      throw ApiError.expired();
    var u =
        find(
            "SELECT u.* FROM users u JOIN auth_sessions s ON s.user_id=u.id WHERE s.access_hash=?"
                + " AND s.access_expires>? AND s.auth_version=u.auth_version",
            digest(h.substring(7)),
            now());
    if (u == null) throw ApiError.expired();
    active(u);
    return u;
  }

  Map<String, Object> user() {
    var u = optional();
    if (u == null) throw ApiError.expired();
    return u;
  }

  Map<String, Object> staff() {
    var u = user();
    if (role(u, "student")) throw ApiError.denied();
    return u;
  }

  Map<String, Object> admin() {
    var u = user();
    if (!role(u, "admin")) throw ApiError.denied();
    return u;
  }

  Map<String, Object> userJson(Map<String, Object> u) {
    var out = only(u, "id full_name username role group_id");
    for (var k : List.of("active", "approved", "must_change_password")) out.put(k, yes(u.get(k)));
    out.put(
        "group_name",
        u.get("group_id") == null ? null : get("groups", u.get("group_id")).get("name"));
    return out;
  }

  boolean seesSubject(Map<String, Object> u, Map<String, Object> s) {
    return role(u, "admin")
        || (role(u, "teacher")
            ? list(s, "teacher_ids").contains(u.get("id"))
            : list(s, "group_ids").contains(u.get("group_id")));
  }

  Map<String, Object> subject(Map<String, Object> u, Object id) {
    var s = get("subjects", id);
    if (!seesSubject(u, s)) throw ApiError.missing();
    return s;
  }

  Set<Object> groupsOf(Map<String, Object> u) {
    var ids = new HashSet<>();
    for (var s : rows("SELECT group_ids,teacher_ids FROM subjects"))
      if (list(s, "teacher_ids").contains(u.get("id"))) ids.addAll(list(s, "group_ids"));
    for (var g : rows("SELECT id FROM groups WHERE owner_id=?", u.get("id"))) ids.add(g.get("id"));
    return ids;
  }

  boolean seesMaterial(Map<String, Object> u, Map<String, Object> m) {
    var groups = list(m, "group_ids");
    return !role(u, "student")
        || yes(m.get("published"))
            && (groups.isEmpty() || groups.contains(u.get("group_id")));
  }

  Map<String, Object> material(Map<String, Object> u, Object id) {
    var m = get("materials", id);
    subject(u, m.get("subject_id"));
    if (!seesMaterial(u, m)) throw ApiError.missing();
    return m;
  }

  void invalidate(Object uid) {
    db.update(
        "UPDATE users SET auth_version=auth_version+1,reset_hash=NULL,reset_expires=NULL WHERE"
            + " id=?",
        uid);
    db.update("DELETE FROM auth_sessions WHERE user_id=?", uid);
  }
}
