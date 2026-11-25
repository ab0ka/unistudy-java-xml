package kz.unistudy;

import static kz.unistudy.Input.*;
import static kz.unistudy.Store.*;

import jakarta.servlet.http.HttpServletResponse;
import java.security.SecureRandom;
import java.util.*;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@Transactional
class Auth {
  private final Store s;
  private final SecureRandom random = new SecureRandom();
  private static final Argon2PasswordEncoder encoder = new Argon2PasswordEncoder(16, 32, 4, 65536, 3);
  private final String dummy;

  Auth(Store s) {
    this.s = s;
    dummy = encoder.encode(token(32));
  }

  private String token(int n) {
    byte[] bytes = new byte[n];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private boolean matches(String raw, Object stored) {
    return encoder.matches(raw, (String) stored);
  }

  static String hashPassword(String raw) {
    return encoder.encode(raw);
  }

  private Map<String, Object> byName(Map<String, Object> body) {
    return s.find("SELECT * FROM users WHERE username=?", name(body));
  }

  private Object session(Map<String, Object> u, HttpServletResponse response) {
    var access = token(32);
    var refresh = token(48);
    s.insert(
        "auth_sessions",
        fields(
            "user_id",
            u.get("id"),
            "auth_version",
            u.get("auth_version"),
            "access_hash",
            digest(access),
            "refresh_hash",
            digest(refresh),
            "access_expires",
            now() + 900,
            "refresh_expires",
            now() + 2592000));
    response.setHeader("Cache-Control", "no-store");
    return fields("access_token", access, "refresh_token", refresh, "user", s.userJson(u));
  }

  @org.springframework.transaction.annotation.Transactional(
      propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
  @GetMapping("/health")
  Object health() {
    return Map.of("status", "ok");
  }

  @PostMapping("/auth/login")
  Object login(@RequestBody Map<String, Object> b, HttpServletResponse r) {
    keys(b, "username password");
    var u = byName(b);
    boolean valid = matches(pwd(b, "password", false), u == null ? dummy : u.get("password_hash"));
    if (!valid || u == null) throw new ApiError(401, "Логин немесе құпиясөз қате.");
    s.active(u);
    s.db.update(
        "DELETE FROM auth_sessions WHERE user_id=? AND refresh_expires<=?", u.get("id"), now());
    return session(u, r);
  }

  @PostMapping("/auth/refresh")
  Object refresh(@RequestBody Map<String, Object> b, HttpServletResponse r) {
    keys(b, "refresh_token");
    var hash = digest(str(b, "refresh_token", 1, 160, false));
    var u =
        s.find(
            "SELECT u.* FROM users u JOIN auth_sessions a ON u.id=a.user_id WHERE a.refresh_hash=?"
                + " AND a.auth_version=u.auth_version AND a.refresh_expires>?",
            hash,
            now());
    if (u == null) throw ApiError.expired();
    s.active(u);
    if (s.db.update("DELETE FROM auth_sessions WHERE refresh_hash=?", hash) != 1)
      throw ApiError.expired();
    return session(u, r);
  }

  @PostMapping("/auth/logout")
  Object logout(@RequestBody Map<String, Object> b) {
    keys(b, "refresh_token");
    s.db.update(
        "DELETE FROM auth_sessions WHERE refresh_hash=?",
        digest(str(b, "refresh_token", 1, 160, false)));
    return OK;
  }

  @GetMapping("/auth/me")
  Object me() {
    return s.userJson(s.user());
  }

  @PostMapping("/auth/register")
  Object register(@RequestBody Map<String, Object> b) {
    keys(b, "username password full_name role group_id");
    var username = name(b);
    var role = choice(b.get("role"), "student", "teacher");
    var group = b.get("group_id");
    if (group != null) {
      if (!role.equals("student")) throw ApiError.invalid();
      group = id(group);
      s.get("groups", group);
    }
    if (byName(b) != null) throw new ApiError(409, "Бұл логин бұрыннан тіркелген.");
    s.insert(
        "users",
        fields(
            "username",
            username,
            "password_hash",
            encoder.encode(pwd(b, "password", true)),
            "full_name",
            str(b, "full_name", 1, 200, true),
            "role",
            role,
            "group_id",
            group,
            "active",
            true,
            "approved",
            false,
            "must_change_password",
            false,
            "auth_version",
            0));
    return Map.of("message", "Өтінім жіберілді. Әкімшінің мақұлдауын күтіңіз.");
  }

  private void current(Map<String, Object> b, Map<String, Object> u) {
    if (!matches(pwd(b, "current_password", false), u.get("password_hash")))
      throw new ApiError(400, "Ағымдағы құпиясөз қате.");
  }

  private Object change(Map<String, Object> b, Map<String, Object> u) {
    var fresh = pwd(b, "new_password", true);
    if (matches(fresh, u.get("password_hash")))
      throw new ApiError(400, "Жаңа құпиясөз бұрынғысынан басқа болуы керек.");
    s.db.update(
        "UPDATE users SET password_hash=?,must_change_password=0 WHERE id=?",
        encoder.encode(fresh),
        u.get("id"));
    s.invalidate(u.get("id"));
    return OK;
  }

  @PostMapping("/auth/password")
  Object password(@RequestBody Map<String, Object> b) {
    keys(b, "current_password new_password");
    var u = s.user();
    current(b, u);
    return change(b, u);
  }

  @PatchMapping("/auth/profile")
  Object profile(@RequestBody Map<String, Object> b) {
    keys(b, "username full_name current_password");
    var u = s.user();
    current(b, u);
    var edits = fields();
    if (b.containsKey("username")) edits.put("username", name(b));
    if (b.containsKey("full_name")) edits.put("full_name", str(b, "full_name", 1, 200, true));
    if (edits.isEmpty()) throw ApiError.invalid();
    s.update("users", u.get("id"), edits);
    return s.userJson(s.get("users", u.get("id")));
  }

  @PostMapping("/auth/reset")
  Object reset(@RequestBody Map<String, Object> b) {
    keys(b, "username code new_password");
    var u = byName(b);
    var hash = digest(str(b, "code", 1, 160, false));
    if (u == null
        || !hash.equals(u.get("reset_hash"))
        || !(u.get("reset_expires") instanceof Number n)
        || n.longValue() <= now())
      throw new ApiError(400, "Код жарамсыз немесе мерзімі аяқталған.");
    s.active(u);
    return change(b, u);
  }

  @GetMapping("/admin/users")
  Object users() {
    s.admin();
    return s.rows("SELECT * FROM users ORDER BY full_name,id").stream().map(s::userJson).toList();
  }

  @PostMapping("/admin/users")
  Object createUser(@RequestBody Map<String, Object> b) {
    s.admin();
    keys(b, "full_name username password role group_id");
    var username = name(b);
    var role = choice(b.get("role"), "teacher", "student");
    Object group = b.get("group_id");
    if (group != null) {
      if (!role.equals("student")) throw ApiError.invalid();
      group = id(group);
      s.get("groups", group);
    }
    if (byName(b) != null) throw new ApiError(409, "Бұл логин бұрыннан тіркелген.");
    int uid = s.insert("users", fields("username", username,
        "full_name", str(b, "full_name", 1, 200, true),
        "password_hash", hashPassword(pwd(b, "password", true)),
        "role", role, "group_id", group, "active", true, "approved", true,
        "must_change_password", false, "auth_version", 0));
    return s.userJson(s.get("users", uid));
  }

  @PostMapping("/admin/users/{id}/approve")
  Object approve(@PathVariable int id) {
    s.admin();
    s.get("users", id);
    s.update("users", id, fields("approved", true));
    return s.userJson(s.get("users", id));
  }

  @PatchMapping("/admin/users/{id}")
  Object edit(@PathVariable int id, @RequestBody Map<String, Object> b) {
    var admin = s.admin();
    var u = s.get("users", id);
    keys(b, "role active group_id");
    var role = choice(b.getOrDefault("role", u.get("role")), "admin", "teacher", "student");
    var active = bool(b.getOrDefault("active", u.get("active")));
    var group = b.getOrDefault("group_id", u.get("group_id"));
    if (Objects.equals(admin.get("id"), id) && (!active || !role.equals("admin")))
      throw new ApiError(400, "Өз әкімші рөліңізді өзгертуге немесе өшіруге болмайды.");
    if (b.get("group_id") != null) {
      if (!role.equals("student")) throw ApiError.invalid();
      group = id(group);
      s.get("groups", group);
    }
    if (!role.equals("student")) group = null;
    if (!Objects.equals(group, u.get("group_id"))
        || active != yes(u.get("active"))
        || !role.equals(u.get("role"))) {
      s.update("users", id, fields("role", role, "active", active, "group_id", group));
      s.invalidate(id);
    }
    return s.userJson(s.get("users", id));
  }

  @PostMapping("/admin/users/{id}/reset")
  Object issue(@PathVariable int id, HttpServletResponse r) {
    s.admin();
    s.active(s.get("users", id));
    var code = token(24);
    s.update("users", id, fields("reset_hash", digest(code), "reset_expires", now() + 1800));
    r.setHeader("Cache-Control", "no-store");
    return Map.of("code", code);
  }
}
