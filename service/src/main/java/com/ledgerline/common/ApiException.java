package com.ledgerline.common;

import org.springframework.http.HttpStatus;

/** Errors that map to an RFC 9457 problem response. {@code code} becomes the problem type. */
public class ApiException extends RuntimeException {
  private final HttpStatus status;
  private final String code;

  public ApiException(HttpStatus status, String code, String detail) {
    super(detail);
    this.status = status;
    this.code = code;
  }

  public HttpStatus status() { return status; }
  public String code() { return code; }

  public static class BadRequest extends ApiException {
    public BadRequest(String code, String detail) { super(HttpStatus.BAD_REQUEST, code, detail); }
  }

  public static class NotFound extends ApiException {
    public NotFound(String what, Object id) { super(HttpStatus.NOT_FOUND, "not-found", what + " " + id + " not found"); }
  }

  public static class Conflict extends ApiException {
    public Conflict(String code, String detail) { super(HttpStatus.CONFLICT, code, detail); }
  }

  /** A well-formed request the business rules refuse (insufficient funds, currency mismatch, ...). */
  public static class Unprocessable extends ApiException {
    public Unprocessable(String code, String detail) { super(HttpStatus.UNPROCESSABLE_ENTITY, code, detail); }
  }
}
