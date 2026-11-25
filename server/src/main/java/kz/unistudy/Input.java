package kz.unistudy;

import static kz.unistudy.ApiError.invalid;

import java.time.*;
import java.util.*;

class Input {
  static void keys(Map<String, Object> m, String allowed) {
    if (!Arrays.asList(allowed.split(" ")).containsAll(m.keySet())) throw invalid();
  }

  static String str(Map<String, Object> m, String k, int min, int max, boolean trim) {
    if (!(m.get(k) instanceof String s)) throw invalid();
    if (trim) s = s.strip();
    if (s.length() < min || s.length() > max) throw invalid();
    return s;
  }

  static String fold(String value) {
    return com.ibm.icu.lang.UCharacter.foldCase(value, true);
  }

  static String name(Map<String, Object> m) {
    return fold(str(m, "username", 3, 80, true));
  }

  static String pwd(Map<String, Object> m, String key, boolean fresh) {
    return str(m, key, fresh ? 8 : 1, 256, false);
  }

  static int num(Object v, int min, int max) {
    try {
      int n = Integer.parseInt(String.valueOf(v));
      if (n >= min && n <= max) return n;
    } catch (NumberFormatException ignored) {
    }
    throw invalid();
  }

  static int id(Object v) {
    return num(v, 1, Integer.MAX_VALUE);
  }

  static boolean bool(Object v) {
    if (v instanceof Boolean b) return b;
    if ("1".equals(v) || Integer.valueOf(1).equals(v) || "true".equalsIgnoreCase(String.valueOf(v)))
      return true;
    if ("0".equals(v)
        || Integer.valueOf(0).equals(v)
        || "false".equalsIgnoreCase(String.valueOf(v))) return false;
    throw invalid();
  }

  static String choice(Object v, String... values) {
    for (var s : values) if (s.equals(v)) return s;
    throw invalid();
  }

  static List<Integer> ids(Object v) {
    if (!(v instanceof List<?> l) || l.size() > 1000) throw invalid();
    return l.stream().map(Input::id).distinct().toList();
  }

  static String date(Object v) {
    var s = String.valueOf(v);
    if (!s.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") || s.startsWith("0000")) throw invalid();
    return LocalDate.parse(s).toString();
  }

  static String deadline(Object v) {
    if (!(v instanceof String s)) throw invalid();
    if (s.length() == 10) return date(v);
    try {
      return OffsetDateTime.parse(s).toString();
    } catch (DateTimeException e) {
      return LocalDateTime.parse(s).toString();
    }
  }

  static String clock(Object v) {
    if (v instanceof String s && s.matches("(?:[01][0-9]|2[0-3]):[0-5][0-9]")) return s;
    throw invalid();
  }
}
