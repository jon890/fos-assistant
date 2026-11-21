package com.bifos.assistant.connector.oauth.presentation;

import com.bifos.assistant.connector.oauth.application.ConnectorOAuthFlows;
import com.bifos.assistant.connector.oauth.presentation.ConnectorOAuthDtos.CompleteRequest;
import com.bifos.assistant.connector.oauth.presentation.ConnectorOAuthDtos.CompletionView;
import com.bifos.assistant.connector.oauth.presentation.ConnectorOAuthDtos.StartRequest;
import com.bifos.assistant.connector.oauth.presentation.ConnectorOAuthDtos.StartView;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.shared.error.ErrorResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 단추 연결의 시작과 돌아오기다. 연결할 사용자는 로그인에서만 정한다(스펙 REQ11). */
@RestController
@RequestMapping("/api/v1/connections")
@RequiredArgsConstructor
public class ConnectorOAuthController {
    private final ConnectorOAuthFlows flows;
    private final CurrentUserProvider currentUser;


    @PostMapping("/{id}/oauth/start")
    public StartView start(@PathVariable String id, @RequestBody StartRequest request) {
        return new StartView(flows.start(currentUser.require(), id, request.redirectUri(), request.values()));
    }


    @PostMapping("/oauth/complete")
    public CompletionView complete(@RequestBody CompleteRequest request) {
        return CompletionView.from(flows.complete(
            currentUser.require(), request.state(), request.code(), request.error(), request.setupAction()));
    }


    /** 역직렬화 오류에도 코드와 칸 값이 든 예외 본문을 응답과 로그에 남기지 않는다. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> unreadableRequest() {
        return ResponseEntity.badRequest()
            .body(new ErrorResponse(ErrorCode.VALIDATION_FAILED.name(), "invalid connector request"));
    }
}
