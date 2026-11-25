package kz.unistudy;

import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

class ApiError extends RuntimeException {
  final int code;

  ApiError(int code, String message) {
    super(message);
    this.code = code;
  }

  static ApiError invalid() {
    return new ApiError(422, "Деректерді тексеріңіз.");
  }

  static ApiError missing() {
    return new ApiError(404, "Жазба табылмады.");
  }

  static ApiError denied() {
    return new ApiError(403, "Бұл әрекетке рұқсат жоқ.");
  }

  static ApiError expired() {
    return new ApiError(401, "Сессия аяқталды. Қайта кіріңіз.");
  }
}

@RestControllerAdvice
class Errors {
  @ExceptionHandler(ApiError.class)
  ResponseEntity<?> error(ApiError e) {
    return ResponseEntity.status(e.code).body(Map.of("detail", e.getMessage()));
  }

  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    ServletRequestBindingException.class,
    MethodArgumentTypeMismatchException.class,
    java.time.DateTimeException.class
  })
  ResponseEntity<?> invalid(Exception e) {
    return error(ApiError.invalid());
  }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  ResponseEntity<?> tooLarge() {
    return error(new ApiError(413, "Файл көлемі 200 МБ-тан аспауы керек."));
  }

  @ExceptionHandler(java.sql.SQLException.class)
  ResponseEntity<?> database(java.sql.SQLException e) {
    // SQLite JDBC reports vendor result codes, including extended constraint codes.
    if ((e.getErrorCode() & 255) == 19) return duplicate();
    System.getLogger(Errors.class.getName())
        .log(System.Logger.Level.ERROR, "Database operation failed", e);
    return error(new ApiError(500, "Сервер деректерді өңдей алмады."));
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  ResponseEntity<?> duplicate() {
    return error(new ApiError(409, "Осындай жазба бұрыннан бар немесе деректер өзгерді."));
  }
}
