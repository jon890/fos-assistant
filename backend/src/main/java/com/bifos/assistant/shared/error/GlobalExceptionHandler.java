package com.bifos.assistant.shared.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApi(ApiException ex) {
        log.warn("api error code={} message={}", ex.code(), ex.getMessage());
        return ResponseEntity.status(ex.code().status())
                .body(new ErrorResponse(ex.code().name(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .orElse("invalid request");
        return ResponseEntity.status(ErrorCode.VALIDATION_FAILED.status())
                .body(new ErrorResponse(ErrorCode.VALIDATION_FAILED.name(), message));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        log.error("unexpected error", ex);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.status())
                .body(new ErrorResponse(ErrorCode.INTERNAL_ERROR.name(), "internal error"));
    }
}
