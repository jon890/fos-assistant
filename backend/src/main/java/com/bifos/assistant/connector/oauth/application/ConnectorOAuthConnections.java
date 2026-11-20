package com.bifos.assistant.connector.oauth.application;

import com.bifos.assistant.connector.connection.application.port.ConnectionOAuth;
import com.bifos.assistant.connector.connection.domain.ConnectorConnection;
import com.bifos.assistant.hermes.application.dto.ConnectorManifest;
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
    public void forget(ConnectorConnection connection) {
        tokens.forget(connection.id());
        alerts.reconnected(connection.userId(), connection.id());
    }
}
