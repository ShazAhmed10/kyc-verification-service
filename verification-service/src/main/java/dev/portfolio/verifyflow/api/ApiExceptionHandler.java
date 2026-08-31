package dev.portfolio.verifyflow.api;

import dev.portfolio.verifyflow.statemachine.IllegalTransitionException;
import dev.portfolio.verifyflow.vendor.VendorCallFailedException;
import java.util.NoSuchElementException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps failures to status codes that mean something.
 *
 * <p>The distinction that matters: an illegal transition is a 409, not a 400 or a 500. The caller's
 * request was well-formed and the server is healthy - the aggregate is simply not in a state where
 * that move is legal, which is a conflict with current state and is often the caller's cue to re-read.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

  @ExceptionHandler(NoSuchElementException.class)
  public ResponseEntity<ApiDtos.ErrorResponse> notFound(NoSuchElementException e) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(new ApiDtos.ErrorResponse("not_found", e.getMessage()));
  }

  @ExceptionHandler(IllegalTransitionException.class)
  public ResponseEntity<ApiDtos.ErrorResponse> illegalTransition(IllegalTransitionException e) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(new ApiDtos.ErrorResponse("illegal_transition", e.getMessage()));
  }

  @ExceptionHandler(VendorCallFailedException.class)
  public ResponseEntity<ApiDtos.ErrorResponse> vendorDown(VendorCallFailedException e) {
    // 502: we are fine, the upstream is not. Distinguishing this from a 500 tells the caller that
    // retrying is reasonable.
    return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
        .body(new ApiDtos.ErrorResponse("vendor_unavailable", e.getMessage()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ApiDtos.ErrorResponse> invalid(MethodArgumentNotValidException e) {
    String detail =
        e.getBindingResult().getFieldErrors().stream()
            .map(f -> f.getField() + ": " + f.getDefaultMessage())
            .findFirst()
            .orElse("invalid request");
    return ResponseEntity.badRequest().body(new ApiDtos.ErrorResponse("invalid_request", detail));
  }
}
