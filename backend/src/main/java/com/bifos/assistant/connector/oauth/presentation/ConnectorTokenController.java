package com.bifos.assistant.connector.oauth.presentation;

import com.bifos.assistant.connector.oauth.application.ConnectorOAuthPassage;
import com.bifos.assistant.connector.oauth.application.dto.ConnectorOAuthPassageAnswer;
import com.bifos.assistant.connector.oauth.application.dto.ConnectorOAuthToken;
import com.bifos.assistant.connector.oauth.presentation.ConnectorOAuthDtos.TokenView;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 단추로 연결한 플러그인 프로세스가 지금 유효한 access token 을 받는 통로다(스펙 REQ6).
 *
 * <p>JWT 없이 받고 경로의 표식이 인증이다. 표식과 토큰이 로그에 남지 않게 실패는 본문 없는 상태 코드로만 답한다.
 */
@RestController
@RequiredArgsConstructor
public class ConnectorTokenController {
    private final ConnectorOAuthPassage passage;


    @GetMapping("/internal/connector-token/{mark}")
    public ResponseEntity<TokenView> token(@PathVariable String mark) {
        ConnectorOAuthPassageAnswer answer = passage.token(mark);
        ConnectorOAuthToken token = answer.token();
        if (token == null) {
            return ResponseEntity.status(HttpStatus.valueOf(answer.status())).cacheControl(CacheControl.noStore()).build();
        }
        Long expiresAt = token.expiresAt() == null ? null : token.expiresAt().getEpochSecond();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new TokenView(token.accessToken(), expiresAt));
    }
}
