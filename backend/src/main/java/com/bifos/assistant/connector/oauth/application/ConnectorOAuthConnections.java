package com.bifos.assistant.connector.oauth.application;

import com.bifos.assistant.connector.connection.application.port.ConnectionOAuth;
import com.bifos.assistant.connector.connection.domain.ConnectorConnection;
import com.bifos.assistant.hermes.application.dto.ConnectorCallError;
import com.bifos.assistant.hermes.application.dto.ConnectorManifest;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 연결의 등록, 확인, 해제가 맡긴 단추 연결의 일을 한다. */
@Component
@RequiredArgsConstructor
public class ConnectorOAuthConnections implements ConnectionOAuth {
    private final ConnectorOAuthApps apps;
    private final ConnectorOAuthSigner signer;
    private final ConnectorOAuthTokens tokens;
    private final ConnectorOAuthAlerts alerts;


    @Override
    public boolean ready(ConnectorManifest manifest) {
        return signer.available() && apps.app(manifest).isPresent();
    }


    @Override
    public Optional<ConnectorCallError> refreshForCheck(long connectionId) {
        return switch (tokens.refresh(connectionId, null).outcome()) {
            case OK -> {
                alerts.recovered(connectionId);
                yield Optional.empty();
            }
            // OAuth 파일이 없다. 막지 않고 확인 도구의 실패로 준비 안 됨을 남겨 다시 연결하게 한다.
            case MISSING -> Optional.empty();
            // 갱신이 이미 다시 연결 필요와 알림을 남겼다.
            case GRANT_REVOKED -> Optional.of(ConnectorCallError.CREDENTIAL_REJECTED);
            default -> Optional.of(ConnectorCallError.UNAVAILABLE);
        };
    }


    @Override
    public void forget(ConnectorConnection connection) {
        tokens.forget(connection.id());
        alerts.reconnected(connection.userId(), connection.id());
    }
}
