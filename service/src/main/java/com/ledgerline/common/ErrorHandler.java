package com.ledgerline.common;

import com.ledgerline.idempotency.IdempotencyInProgressException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;

import java.net.URI;
import java.util.stream.Collectors;

@RestControllerAdvice
public class ErrorHandler {
  private static final Logger log = LoggerFactory.getLogger(ErrorHandler.class);

  @ExceptionHandler(IdempotencyInProgressException.class)
  ResponseEntity<ProblemDetail> inProgress(IdempotencyInProgressException e) {
    return ResponseEntity.status(e.status()).header("Retry-After", "1").body(problem(e.status(), e.code(), e.getMessage()));
  }

  @ExceptionHandler(ApiException.class)
  ResponseEntity<ProblemDetail> api(ApiException e) {
    return ResponseEntity.status(e.status()).body(problem(e.status(), e.code(), e.getMessage()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ProblemDetail> invalid(MethodArgumentNotValidException e) {
    String detail = e.getBindingResult().getFieldErrors().stream()
        .map(f -> f.getField() + " " + f.getDefaultMessage()).sorted().collect(Collectors.joining("; "));
    return ResponseEntity.badRequest().body(problem(HttpStatus.BAD_REQUEST, "validation", detail));
  }

  @ExceptionHandler({MissingRequestHeaderException.class, HttpMessageNotReadableException.class,
      MethodArgumentTypeMismatchException.class})
  ResponseEntity<ProblemDetail> badRequest(Exception e) {
    return ResponseEntity.badRequest().body(problem(HttpStatus.BAD_REQUEST, "bad-request", e.getMessage()));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ProblemDetail> unexpected(Exception e) {
    // Spring MVC's own errors (404 no route, 405, 415, ...) already know their status
    if (e instanceof org.springframework.web.ErrorResponse er) {
      HttpStatus status = HttpStatus.valueOf(er.getStatusCode().value());
      return ResponseEntity.status(status).body(problem(status, "bad-request", er.getBody().getDetail()));
    }
    log.error("unhandled error", e);
    return ResponseEntity.internalServerError()
        .body(problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal", "Unexpected error"));
  }

  private static ProblemDetail problem(HttpStatus status, String code, String detail) {
    ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detail);
    p.setType(URI.create("https://ledgerline.dev/problems/" + code));
    p.setTitle(status.getReasonPhrase());
    return p;
  }
}
