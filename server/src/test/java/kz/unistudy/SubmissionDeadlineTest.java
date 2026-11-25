package kz.unistudy;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class SubmissionDeadlineTest {
  @Test void closesAtExactInstantAndAllowsLegacyMissingDeadline() {
    var deadline = Store.fields("deadline", "2026-11-24T18:00:00+05:00");
    assertFalse(SubmissionDeadline.closed(deadline, Instant.parse("2026-11-24T12:59:59.999999999Z")));
    assertTrue(SubmissionDeadline.closed(deadline, Instant.parse("2026-11-24T13:00:00Z")));
    assertTrue(SubmissionDeadline.closed(deadline, Instant.parse("2026-11-24T13:00:00.000000001Z")));
    assertFalse(SubmissionDeadline.closed(Store.fields("deadline", null), Instant.parse("2099-01-01T00:00:00Z")));
    assertEquals(Instant.parse("2026-11-24T13:00:00Z"), SubmissionDeadline.instant("2026-11-24T18:00:00"));
    assertEquals(Instant.parse("2026-11-24T19:00:00Z"), SubmissionDeadline.instant("2026-11-24"));
  }
}
