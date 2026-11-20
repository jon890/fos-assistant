package com.bifos.assistant.connector.oauth.presentation;

import com.bifos.assistant.connector.oauth.application.ConnectorOAuthPassage;
import com.bifos.assistant.connector.oauth.application.dto.ConnectorOAuthRefresh;
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
 * <p>JWT 없이 받고 경로의 표식이 인증이다. 표식과 토큰이 로그에 남지 않게 실패는 본문 없는 상태 코드로만 답한다. 표식이 틀리면 401,
 * 연결이 없거나 해제됐으면 404, 다시 연결 필요면 409, 갱신하지 못했으면 503 이다.
 */
@RestController
@RequiredArgsConstructor
public class ConnectorTokenController {
    private final ConnectorOAuthPassage passage;


    @GetMapping("/internal/connector-token/{mark}")
    public ResponseEntity<TokenView> token(@PathVariable String mark) {
        ConnectorOAuthRefresh answer = passage.token(mark).orElse(null);
        HttpStatus status = answer == null ? HttpStatus.UNAUTHORIZED : switch (answer.outcome()) {
            case OK -> HttpStatus.OK;
            case MISSING -> HttpStatus.NOT_FOUND;
            case GRANT_REVOKED -> HttpStatus.CONFLICT;
            default -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        ResponseEntity.BodyBuilder response = ResponseEntity.status(status).cacheControl(CacheControl.noStore());
        if (status != HttpStatus.OK) {
            return response.build();
        }
        Long expiresAt = answer.token().expiresAt() == null ? null : answer.token().expiresAt().getEpochSecond();
        return response.body(new TokenView(answer.token().accessToken(), expiresAt));
    }
}
