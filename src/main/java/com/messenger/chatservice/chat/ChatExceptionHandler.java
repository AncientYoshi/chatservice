package com.messenger.chatservice.chat;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class ChatExceptionHandler {
  @ExceptionHandler(ChatException.class)
  public ResponseEntity<?> chat(ChatException e) {
    return ResponseEntity.status(e.status).body(Map.of("code", e.code, "message", e.getMessage()));
  }

  @ExceptionHandler({
    org.springframework.http.converter.HttpMessageNotReadableException.class,
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
    org.springframework.web.bind.MissingServletRequestParameterException.class
  })
  public ResponseEntity<?> bad(Exception e) {
    return ResponseEntity.badRequest()
        .body(Map.of("code", "INVALID_REQUEST", "message", "Invalid request body or parameters"));
  }

  @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
  public ResponseEntity<?> missing(Exception e) {
    return ResponseEntity.status(404)
        .body(Map.of("code", "NOT_FOUND", "message", "Route not found"));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<?> unexpected(Exception e) {
    if (e instanceof org.springframework.web.ErrorResponse response) {
      return ResponseEntity.status(response.getStatusCode())
          .body(Map.of("code", "REQUEST_REJECTED", "message", "Request is not supported"));
    }
    org.slf4j.LoggerFactory.getLogger(ChatExceptionHandler.class)
        .error("Unhandled API error (type={})", e.getClass().getSimpleName());
    return ResponseEntity.status(500)
        .body(Map.of("code", "INTERNAL_ERROR", "message", "An unexpected error occurred"));
  }
}
