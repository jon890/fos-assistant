package com.bifos.assistant.connector.oauth.application;

import com.bifos.assistant.connector.connection.domain.ConnectorConnection;
import com.bifos.assistant.connector.connection.domain.type.ConnectionStatus;
import com.bifos.assistant.connector.connection.infra.ConnectorConnectionRepository;
import com.bifos.assistant.connector.oauth.application.dto.ConnectorOAuthMark;
import com.bifos.assistant.connector.oauth.application.dto.ConnectorOAuthPassageAnswer;
import com.bifos.assistant.connector.oauth.application.dto.ConnectorOAuthRefresh;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 토큰 통로의 표식을 확인하고 그 연결의 토큰만 내준다(설계 「토큰 통로 표식」).
 *
 * <p>표식은 가진 쪽이 곧 권한이다. 통로가 막는 것은 번호를 바꾸거나 위조한 표식이다.
 */
@Component
@RequiredArgsConstructor
public class ConnectorOAuthPassage {
    /** 현재 칸이 이 시간 안에 만료되면 내주기 전에 갱신한다. */
    private static final Duration FRESH = Duration.ofMinutes(1);
    private static final int UNAUTHORIZED = 401;
    private static final int NOT_FOUND = 404;
    private static final int CONFLICT = 409;
    private static final int UNAVAILABLE = 503;

    private final ConnectorOAuthSigner signer;
    private final ConnectorOAuthTokens tokens;
    private final ConnectorConnectionRepository connections;


    public ConnectorOAuthPassageAnswer token(String token) {
        ConnectorOAuthMark mark = signer.verifyMark(token).orElse(null);
        if (mark == null) {
            return failed(UNAUTHORIZED);
        }
        if (mark.nonce() != null) {
            // 대기 표식은 확인 도구가 쓴다. 연결 상태를 보지 않는다.
            return tokens.pending(mark.connectionId(), mark.nonce()).map(found -> new ConnectorOAuthPassageAnswer(200, found)).orElse(failed(NOT_FOUND));
        }
        ConnectorConnection connection = connections.findById(mark.connectionId()).orElse(null);
        if (connection == null || connection.status() == ConnectionStatus.DISCONNECTED || !connection.fields().auth().usesOAuth()) {
            return failed(NOT_FOUND);
        }
        if (connection.fields().auth().reconnectRequired()) {
            return failed(CONFLICT);
        }
        ConnectorOAuthRefresh refreshed = tokens.refresh(mark.connectionId(), FRESH);
        return switch (refreshed.outcome()) {
            case OK -> new ConnectorOAuthPassageAnswer(200, refreshed.token());
            case GRANT_REVOKED -> failed(CONFLICT);
            case MISSING -> failed(NOT_FOUND);
            default -> failed(UNAVAILABLE);
        };
    }


    private static ConnectorOAuthPassageAnswer failed(int status) {
        return new ConnectorOAuthPassageAnswer(status, null);
    }
}
