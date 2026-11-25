package kz.unistudy;

import java.time.*;
import java.util.Map;

/** All comparisons use server time; legacy local dates belong to Qyzylorda. */
final class SubmissionDeadline {
  static final ZoneId ZONE = ZoneId.of("Asia/Qyzylorda");

  static Instant instant(String value) {
    if (value.length() == 10) return LocalDate.parse(value).plusDays(1).atStartOfDay(ZONE).toInstant();
    try { return OffsetDateTime.parse(value).toInstant(); }
    catch (DateTimeException ignored) { return LocalDateTime.parse(value).atZone(ZONE).toInstant(); }
  }

  static boolean closed(Map<String, Object> material, Instant now) {
    Object value = material.get("deadline");
    return value != null && !value.toString().isBlank() && !now.isBefore(instant(value.toString()));
  }

  static void requireOpen(Map<String, Object> material, Instant now) {
    if (closed(material, now)) throw new ApiError(409, "Тапсыру мерзімі аяқталды. Жұмысты өзгертуге болмайды.");
  }
}
