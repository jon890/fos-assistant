package com.bifos.assistant.shared.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApi(ApiException ex) {
        log.warn("api error code={} message={}", ex.code(), ex.getMessage());
        return ResponseEntity.status(ex.code().status())
                .body(new ErrorResponse(ex.code().name(), ex.getMessage(), ex.missingToolsets()));
    }

    @ExceptionHandler(PessimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleAgentLock(PessimisticLockingFailureException ex) {
        return ResponseEntity.status(ErrorCode.AGENT_BUSY.status())
                .body(new ErrorResponse(ErrorCode.AGENT_BUSY.name(), "the agent is being updated"));
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
     * 경로 변수나 요청 인자의 형식이 틀린 요청을 입력 오류로 돌려준다.
     *
     * <p>대화의 공개 식별자 자리에 UUID 가 아닌 값이 오면 여기 걸린다. 그대로 두면 500 으로 끝난다.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.status(ErrorCode.VALIDATION_FAILED.status())
                .body(new ErrorResponse(ErrorCode.VALIDATION_FAILED.name(), ex.getName() + " has an invalid format"));
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

    /**
     * 경로가 받지 않는 메서드로 온 요청을 405 로 돌려준다. 받는 메서드는 {@code Allow} 헤더에 적는다.
     *
     * <p>{@code @GetMapping} 경로는 Spring 이 HEAD 도 받으므로 여기 오지 않는다. 인증 없이 닿는 POST 전용
     * 경로에 HEAD 나 GET 으로 두드린 요청이 여기 온다. 서버 결함이 아니므로 ERROR 로 남기지 않는다.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        log.warn("method not allowed method={} supported={}", ex.getMethod(), ex.getSupportedHttpMethods());
        var supported = ex.getSupportedHttpMethods();
        return ResponseEntity.status(ErrorCode.METHOD_NOT_ALLOWED.status())
                .allow(supported == null ? new HttpMethod[0] : supported.toArray(HttpMethod[]::new))
                .body(new ErrorResponse(ErrorCode.METHOD_NOT_ALLOWED.name(),
                        "method " + ex.getMethod() + " is not supported"));
    }

    /** 이미 쓸 수 없는 비동기 응답에는 오류 본문을 쓰지 않는다. */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleAsyncRequestNotUsable(AsyncRequestNotUsableException ex) {
        log.debug("async response is no longer usable: {}", ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        log.error("unexpected error", ex);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.status())
                .body(new ErrorResponse(ErrorCode.INTERNAL_ERROR.name(), "internal error"));
    }
}
