package com.bifos.assistant.shared.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

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

    /**
     * multipart 상한을 넘은 요청을 입력 오류로 돌려준다.
     *
     * <p>상한은 서비스의 한 장 상한보다 조금 크게 두었다. 그보다 큰 요청이 여기 걸리며, 그대로 두면
     * 500 으로 끝나 화면이 까닭을 알 수 없다.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleUploadTooLarge(MaxUploadSizeExceededException ex) {
        log.warn("upload rejected by the multipart limit: {}", ex.getMessage());
        return ResponseEntity.status(ErrorCode.VALIDATION_FAILED.status())
                .body(new ErrorResponse(ErrorCode.VALIDATION_FAILED.name(), "the upload is larger than the limit"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        log.error("unexpected error", ex);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.status())
                .body(new ErrorResponse(ErrorCode.INTERNAL_ERROR.name(), "internal error"));
    }
}
