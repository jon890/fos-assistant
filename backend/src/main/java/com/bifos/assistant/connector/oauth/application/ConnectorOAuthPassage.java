package com.bifos.assistant.connector.oauth.application;

import com.bifos.assistant.connector.connection.domain.ConnectorConnection;
import com.bifos.assistant.connector.connection.domain.type.ConnectionStatus;
import com.bifos.assistant.connector.connection.infra.ConnectorConnectionRepository;
import com.bifos.assistant.connector.oauth.application.dto.ConnectorOAuthMark;
import com.bifos.assistant.connector.oauth.application.dto.ConnectorOAuthRefresh;
import com.bifos.assistant.hermes.application.dto.ConnectorOAuth.Outcome;
import java.time.Duration;
import java.util.Optional;
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

    private final ConnectorOAuthSigner signer;
    private final ConnectorOAuthTokens tokens;
    private final ConnectorConnectionRepository connections;


    /**
     * 표식이 가리키는 연결의 토큰이나 주지 못한 까닭이다. 연결이 없거나 해제됐거나 단추 연결이 아니면 {@code MISSING}, 다시 연결 필요면
     * {@code GRANT_REVOKED} 다.
     *
     * @return 표식이 틀렸으면 비어 있다
     */
    public Optional<ConnectorOAuthRefresh> token(String token) {
        ConnectorOAuthMark mark = signer.verifyMark(token).orElse(null);
        if (mark == null) {
            return Optional.empty();
        }
        if (mark.nonce() != null) {
            // 대기 표식은 확인 도구가 쓴다. 연결 상태를 보지 않는다.
            return Optional.of(tokens.pending(mark.connectionId(), mark.nonce())
                .map(found -> new ConnectorOAuthRefresh(Outcome.OK, found)).orElse(new ConnectorOAuthRefresh(Outcome.MISSING, null)));
        }
        ConnectorConnection connection = connections.findById(mark.connectionId()).orElse(null);
        if (connection == null || connection.status() == ConnectionStatus.DISCONNECTED || !connection.fields().auth().usesOAuth()) {
            return Optional.of(new ConnectorOAuthRefresh(Outcome.MISSING, null));
        }
        if (connection.fields().auth().reconnectRequired()) {
            return Optional.of(new ConnectorOAuthRefresh(Outcome.GRANT_REVOKED, null));
        }
        return Optional.of(tokens.refresh(mark.connectionId(), FRESH));
    }

}
