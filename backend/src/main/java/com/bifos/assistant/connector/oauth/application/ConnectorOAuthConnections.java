package com.bifos.assistant.connector.oauth.application;

import com.bifos.assistant.connector.connection.application.dto.ConnectionOAuthRevoke;
import com.bifos.assistant.connector.connection.application.dto.ConnectionOAuthRevoke.Status;
import com.bifos.assistant.connector.connection.application.port.ConnectionOAuth;
import com.bifos.assistant.connector.connection.domain.ConnectorConnection;
import com.bifos.assistant.connector.connection.domain.type.ConnectionStatus;
import com.bifos.assistant.connector.connection.infra.ConnectorConnectionRepository;
import com.bifos.assistant.connector.oauth.application.dto.ConnectorOAuthApp;
import com.bifos.assistant.hermes.application.dto.ConnectorCallError;
import com.bifos.assistant.hermes.application.dto.ConnectorManifest;
import com.bifos.assistant.hermes.application.dto.ConnectorOAuth.Outcome;
import com.bifos.assistant.hermes.application.dto.ConnectorOAuthDeclaration;
import com.bifos.assistant.hermes.application.dto.ConnectorOAuthDeclaration.AppScope;
import com.bifos.assistant.hermes.application.port.HermesConnectorOAuthClient;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** 연결의 등록, 확인, 해제가 맡긴 단추 연결의 일을 한다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConnectorOAuthConnections implements ConnectionOAuth {
    private final ConnectorConnectionRepository connections;
    private final HermesConnectorOAuthClient oauth;
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
            // 다시 연결 필요였으면 갱신이 이미 표시를 지우고 끊김 알림을 읽음으로 바꿨다.
            case OK -> Optional.empty();
            // OAuth 파일이 없다. 막지 않고 확인 도구의 실패로 준비 안 됨을 남겨 다시 연결하게 한다.
            case MISSING -> Optional.empty();
            // 갱신이 이미 다시 연결 필요와 알림을 남겼다.
            case GRANT_REVOKED -> Optional.of(ConnectorCallError.CREDENTIAL_REJECTED);
            default -> Optional.of(ConnectorCallError.UNAVAILABLE);
        };
    }


    /**
     * 취소 범위는 {@code docs/features/connector-oauth/design.md} 의 「취소 범위」 가 갖는다. 공유 앱은 토큰 하나를 취소하면 그 앱의 허락
     * 전체가 사라져, 같은 사용자의 다른 연결이 그 앱을 쓰고 있으면 취소하지 않는다.
     */
    @Override
    public ConnectionOAuthRevoke revoke(ConnectorConnection connection, Optional<ConnectorManifest> manifest) {
        if (!connection.fields().auth().usesOAuth()) {
            return null;
        }
        ConnectorOAuthDeclaration declared = manifest.map(ConnectorManifest::oauth).orElse(null);
        if (declared == null) {
            // 카탈로그에서 빠졌거나 선언이 사라져 어느 앱의 허락인지 알 수 없다.
            return new ConnectionOAuthRevoke(Status.FAILED, null, null);
        }
        ConnectionOAuthRevoke failed = new ConnectionOAuthRevoke(Status.FAILED, declared.settingsUrl(), declared.installSettingsUrl());
        try {
            ConnectorOAuthApp app = apps.app(manifest.get()).orElse(null);
            if (app == null || (declared.appScope() == AppScope.SHARED && sharedByAnother(connection))) {
                return failed;
            }
            // 메모리의 access token 은 넘기지 않는다. 그 사이 갱신으로 무효가 됐거나 만료됐을 수 있다. 대시보드가 보관 파일 잠금 안에서
            // 최신 refresh token 으로 새로 받아 취소한다.
            Outcome outcome = oauth.revoke(connection.vault(), connection.connectorId(), app.credentials(), null);
            if (outcome != Outcome.OK) {
                log.warn("connector OAuth revoke did not succeed connection={} outcome={}", connection.id(), outcome);
                return failed;
            }
            return new ConnectionOAuthRevoke(Status.REVOKED, null, declared.installSettingsUrl());
        } catch (RuntimeException ex) {
            // 예외 메시지와 원격 응답에는 토큰과 앱 자격이 섞일 수 있어 종류만 남긴다.
            log.warn("connector OAuth revoke failed connection={} exception={}", connection.id(), ex.getClass().getSimpleName());
            return failed;
        }
    }


    /** 같은 사용자의 다른 쓸 수 있는 단추 연결이 같은 제공자를 쓰는가. */
    private boolean sharedByAnother(ConnectorConnection connection) {
        String provider = connection.fields().auth().provider();
        return connections.findByUserId(connection.userId()).stream()
            .anyMatch(other -> !other.id().equals(connection.id()) && other.status() == ConnectionStatus.READY
                && provider.equals(other.fields().auth().provider()));
    }


    @Override
    public void forget(ConnectorConnection connection) {
        tokens.forget(connection.id());
        alerts.reconnected(connection.userId(), connection.id());
    }
}
