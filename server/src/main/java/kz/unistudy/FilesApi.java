package kz.unistudy;

import static kz.unistudy.Input.*;
import static kz.unistudy.Store.*;

import java.time.Instant;
import java.util.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
@Transactional
class FilesApi {
  final Store s;
  final Blobs blobs;

  FilesApi(Store s, Blobs blobs) {
    this.s = s;
    this.blobs = blobs;
  }

  @PostMapping("/materials/{id}/files")
  Object upload(@PathVariable int id, @RequestParam MultipartFile file) {
    s.material(s.staff(), id);
    return blobs.upload(false, id, file);
  }

  @DeleteMapping("/files/{id}")
  Object remove(@PathVariable int id) {
    var u = s.staff();
    var f = s.get("attachments", id);
    s.material(u, f.get("material_id"));
    s.db.update("DELETE FROM attachments WHERE id=?", id);
    blobs.afterDelete((String) f.get("key"));
    return OK;
  }

  @GetMapping("/files/{id}/download")
  Object download(@PathVariable int id) {
    var u = s.user();
    var f = s.get("attachments", id);
    s.material(u, f.get("material_id"));
    return blobs.download(f);
  }

  Map<String, Object> assignment(Map<String, Object> u, Object id) {
    var material = s.material(u, id);
    if (!"assignment".equals(material.get("section"))) throw ApiError.missing();
    return material;
  }

  Map<String, Object> submission(Map<String, Object> u, Object id, boolean change) {
    if (change && !role(u, "student")) throw ApiError.denied();
    var sub = s.get("submissions", id);
    if (role(u, "student") && !Objects.equals(sub.get("student_id"), u.get("id")))
      throw ApiError.missing();
    var material = assignment(u, sub.get("material_id"));
    if (change) SubmissionDeadline.requireOpen(material, Instant.now());
    return sub;
  }

  Map<String, Object> serialize(Map<String, Object> sub) {
    var out = only(sub, "id material_id student_id text updated_at");
    out.put("student_name", s.get("users", sub.get("student_id")).get("full_name"));
    out.put(
        "files",
        s.rows(
            "SELECT id,name,size,mime,'/submission-files/'||id||'/download' AS download_path FROM"
                + " submission_files WHERE submission_id=? ORDER BY id",
            sub.get("id")));
    return out;
  }

  @GetMapping("/materials/{id}/submissions")
  Object submissions(@PathVariable int id) {
    var u = s.user();
    assignment(u, id);
    var rows =
        role(u, "student")
            ? s.rows(
                "SELECT * FROM submissions WHERE material_id=? AND student_id=? ORDER BY id",
                id,
                u.get("id"))
            : s.rows("SELECT * FROM submissions WHERE material_id=? ORDER BY id", id);
    return rows.stream().map(this::serialize).toList();
  }

  @PutMapping("/materials/{id}/submission")
  Object submit(@PathVariable int id, @RequestBody Map<String, Object> b) {
    keys(b, "text");
    var u = s.user();
    if (!role(u, "student")) throw ApiError.denied();
    SubmissionDeadline.requireOpen(assignment(u, id), Instant.now());
    s.db.update(
        "INSERT INTO submissions(material_id,student_id,text,updated_at) VALUES(?,?,?,?) ON"
            + " CONFLICT(material_id,student_id) DO UPDATE SET"
            + " text=excluded.text,updated_at=excluded.updated_at",
        id,
        u.get("id"),
        str(b, "text", 0, 20000, false),
        Instant.now().toString());
    return serialize(
        s.find("SELECT * FROM submissions WHERE material_id=? AND student_id=?", id, u.get("id")));
  }

  @PostMapping("/submissions/{id}/files")
  Object uploadSubmission(@PathVariable int id, @RequestParam MultipartFile file) {
    var u = s.user();
    submission(u, id, true);
    var result = blobs.upload(true, id, file);
    // A slow copy can cross the deadline. Throw inside this transaction so both
    // the inserted row and Blobs rollback cleanup remove the rejected upload.
    submission(u, id, true);
    s.update("submissions", id, fields("updated_at", Instant.now().toString()));
    return result;
  }

  @DeleteMapping("/submission-files/{id}")
  Object removeSubmission(@PathVariable int id) {
    var u = s.user();
    var f = s.get("submission_files", id);
    submission(u, f.get("submission_id"), true);
    s.update("submissions", f.get("submission_id"), fields("updated_at", Instant.now().toString()));
    s.db.update("DELETE FROM submission_files WHERE id=?", id);
    blobs.afterDelete((String) f.get("key"));
    return OK;
  }

  @GetMapping("/submission-files/{id}/download")
  Object downloadSubmission(@PathVariable int id) {
    var u = s.user();
    var f = s.get("submission_files", id);
    submission(u, f.get("submission_id"), false);
    return blobs.download(f);
  }
}
