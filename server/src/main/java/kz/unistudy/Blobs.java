package kz.unistudy;

import static kz.unistudy.Store.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.*;
import org.springframework.web.multipart.MultipartFile;

@Component
class Blobs {
  private final Path root;
  private final Store s;

  Blobs(Path root, Store s) {
    this.root = root;
    this.s = s;
  }

  private static final Map<String, String> TYPES =
      Map.ofEntries(
          Map.entry("doc", "application/msword"),
          Map.entry(
              "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
          Map.entry("pdf", "application/pdf"),
          Map.entry("txt", "text/plain"),
          Map.entry("png", "image/png"),
          Map.entry("jpg", "image/jpeg"),
          Map.entry("jpeg", "image/jpeg"),
          Map.entry("gif", "image/gif"),
          Map.entry("webp", "image/webp"),
          Map.entry("mp4", "video/mp4"));
  private static final System.Logger LOG = System.getLogger(Blobs.class.getName());

  private Path path(String key) {
    if (key == null || key.isBlank() || key.indexOf('/') >= 0 || key.indexOf((char) 92) >= 0)
      throw ApiError.missing();
    Path path = root.resolve(key).normalize();
    if (!root.equals(path.getParent())) throw ApiError.missing();
    return path;
  }

  private void remove(Path p) {
    try {
      Files.deleteIfExists(p);
    } catch (IOException e) {
      LOG.log(System.Logger.Level.WARNING, "Storage cleanup failed", e);
    }
  }

  void afterDelete(String key) {
    Path p = path(key);
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          public void afterCommit() {
            remove(p);
          }
        });
  }

  Map<String, Object> upload(boolean submission, int owner, MultipartFile f) {
    String raw = Objects.toString(f.getOriginalFilename(), "").replace((char) 92, '/');
    String name = raw.substring(raw.lastIndexOf('/') + 1).strip();
    if (name.isEmpty()
        || name.equals(".")
        || name.equals("..")
        || name.length() > 400
        || name.chars().anyMatch(ch -> ch < 32)) throw ApiError.invalid();
    if (f.getSize() < 1 || f.getSize() > 200L * 1024 * 1024)
      throw new ApiError(413, "Файл көлемі 1 байт пен 200 МБ аралығында болуы керек.");
    int dot = name.lastIndexOf('.');
    String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    String mime = TYPES.get(ext);
    String supplied =
        Objects.toString(f.getContentType(), "").split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    if (mime == null
        || !supplied.isEmpty()
            && !supplied.equals("application/octet-stream")
            && !supplied.equals(mime)) throw unsupported();
    String key = UUID.randomUUID().toString().replace("-", "");
    Path target = path(key);
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          public void afterCompletion(int status) {
            if (status != STATUS_COMMITTED) remove(target);
          }
        });
    try (var in = f.getInputStream()) {
      long size = Files.copy(in, target);
      if (size != f.getSize()) throw new IOException("Upload length mismatch");
      if (!valid(target, ext)) throw unsupported();
      int id =
          s.insert(
              submission ? "submission_files" : "attachments",
              fields(
                  submission ? "submission_id" : "material_id",
                  owner,
                  "name",
                  name,
                  "size",
                  size,
                  "key",
                  key,
                  "mime",
                  mime));
      var result = fields("id", id, "name", name, "size", size, "mime", mime);
      if (submission) result.put("download_path", "/submission-files/" + id + "/download");
      return result;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private ApiError unsupported() {
    return new ApiError(415, "Файл түрін немесе мазмұнын тексеріңіз.");
  }

  private boolean valid(Path p, String ext) throws IOException {
    byte[] head;
    try (var in = Files.newInputStream(p)) {
      head = in.readNBytes(1024);
    }
    String text = new String(head, StandardCharsets.ISO_8859_1);
    String hex = HexFormat.of().formatHex(head, 0, Math.min(8, head.length));
    return switch (ext) {
      case "doc" -> hex.startsWith("d0cf11e0a1b11ae1");
      case "pdf" -> text.startsWith("%PDF-");
      case "png" -> hex.startsWith("89504e470d0a1a0a");
      case "jpg", "jpeg" -> hex.startsWith("ffd8ff");
      case "gif" -> text.startsWith("GIF87a") || text.startsWith("GIF89a");
      case "webp" -> text.startsWith("RIFF") && text.startsWith("WEBP", 8);
      case "mp4" -> text.startsWith("ftyp", 4);
      case "txt" -> text.indexOf(0) < 0;
      case "docx" -> {
        try (var z = new ZipFile(p.toFile())) {
          yield z.getEntry("[Content_Types].xml") != null
              && z.getEntry("word/document.xml") != null;
        } catch (java.util.zip.ZipException e) {
          yield false;
        }
      }
      default -> false;
    };
  }

  ResponseEntity<FileSystemResource> download(Map<String, Object> f) {
    var p = path((String) f.get("key"));
    if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) throw ApiError.missing();
    return ResponseEntity.ok()
        .header("Cache-Control", "private, no-store")
        .header("X-Content-Type-Options", "nosniff")
        .header(
            "Content-Disposition",
            ContentDisposition.attachment()
                .filename((String) f.get("name"), StandardCharsets.UTF_8)
                .build()
                .toString())
        .contentType(MediaType.parseMediaType((String) f.get("mime")))
        .body(new FileSystemResource(p));
  }
}
