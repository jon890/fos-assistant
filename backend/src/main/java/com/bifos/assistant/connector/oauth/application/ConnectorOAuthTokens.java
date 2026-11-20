package com.bifos.assistant.connector.oauth.application;

import com.bifos.assistant.connector.connection.application.ConnectorManifests;
import com.bifos.assistant.connector.connection.domain.ConnectorConnection;
import com.bifos.assistant.connector.connection.domain.type.ConnectionStatus;
import com.bifos.assistant.connector.connection.infra.ConnectorConnectionRepository;
import com.bifos.assistant.connector.oauth.application.dto.ConnectorOAuthApp;
import com.bifos.assistant.connector.oauth.application.dto.ConnectorOAuthRefresh;
import com.bifos.assistant.connector.oauth.application.dto.ConnectorOAuthToken;
import com.bifos.assistant.hermes.application.dto.ConnectorManifest;
import com.bifos.assistant.hermes.application.dto.ConnectorOAuth.Outcome;
import com.bifos.assistant.hermes.application.dto.ConnectorOAuth.Token;
import com.bifos.assistant.hermes.application.port.HermesConnectorClient;
import com.bifos.assistant.hermes.application.port.HermesConnectorOAuthClient;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 단추 연결의 access token 을 메모리에 두고 갱신한다(스펙 REQ6, 설계 「갱신 주기」).
 *
 * <p>현재 칸은 연결마다 하나이고 토큰 통로가 내준다. 대기 칸은 확인 전 흐름의 것이라 돌고 있는 바인딩에 가지 않는다. 재기동하면 비고 다음
 * 갱신이 다시 채운다(서버 한 대 전제). 토큰은 로그와 예외 메시지에 쓰지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConnectorOAuthTokens {
    private final ConnectorConnectionRepository connections;
    private final HermesConnectorClient connectors;
    private final HermesConnectorOAuthClient oauth;
    private final ConnectorOAuthApps apps;
    private final ConnectorOAuthAlerts alerts;
    private final Clock clock;

    /** 이 시간 안에 만료되는 토큰을 예약 갱신이 새로 받는다. 주기(10분)보다 길어 한 주기를 놓쳐도 만료 전에 다시 본다. */
    static final Duration AHEAD = Duration.ofMinutes(15);

    private final Map<Long, ConnectorOAuthToken> current = new ConcurrentHashMap<>();
    private final Map<String, ConnectorOAuthToken> pending = new ConcurrentHashMap<>();
    private final Map<Long, Object> locks = new ConcurrentHashMap<>();


    public Optional<ConnectorOAuthToken> current(long connectionId) {
        return Optional.ofNullable(current.get(connectionId));
    }


    public Optional<ConnectorOAuthToken> pending(long connectionId, String nonce) {
        return Optional.ofNullable(pending.get(pendingKey(connectionId, nonce)));
    }


    public void putPending(long connectionId, String nonce, ConnectorOAuthToken token) {
        pending.put(pendingKey(connectionId, nonce), token);
    }


    /** 확인한 흐름의 토큰을 현재 칸으로 옮긴다. */
    public void promote(long connectionId, String nonce) {
        ConnectorOAuthToken token = pending.remove(pendingKey(connectionId, nonce));
        if (token != null) {
            current.put(connectionId, token);
        }
    }


    public void dropPending(long connectionId, String nonce) {
        pending.remove(pendingKey(connectionId, nonce));
    }


    /** 해제했거나 붙여 넣기로 바꾼 연결의 현재 칸을 지운다. */
    public void forget(long connectionId) {
        current.remove(connectionId);
    }


    /**
     * 그 연결의 access token 을 새로 받는다. 연결마다 한 번에 하나만 돌고, 기다린 요청은 앞 갱신이 넣은 칸을 쓴다. 트랜잭션 밖에서 부른다.
     *
     * <p>결과별 처리는 설계 「끊김과 알림」 과 같다. 서비스가 갱신을 거절했을 때만 연결을 다시 연결 필요로 바꾼다. 대시보드에 닿지 못했거나
     * 서비스가 잠시 답하지 않으면 아무것도 바꾸지 않는다(스펙 DECISION13).
     *
     * @param ahead 현재 칸이 이 시간 뒤에도 쓸 수 있으면 그대로 돌려준다. null 이면 늘 새로 받는다
     */
    public ConnectorOAuthRefresh refresh(long connectionId, Duration ahead) {
        synchronized (locks.computeIfAbsent(connectionId, id -> new Object())) {
            ConnectorOAuthToken held = current.get(connectionId);
            if (ahead != null && held != null && held.validFor(ahead, clock.instant())) {
                return new ConnectorOAuthRefresh(Outcome.OK, held);
            }
            ConnectorConnection connection = connections.findById(connectionId).orElse(null);
            if (connection == null || connection.status() == ConnectionStatus.DISCONNECTED
                || !connection.fields().auth().usesOAuth()) {
                current.remove(connectionId);
                return new ConnectorOAuthRefresh(Outcome.MISSING, null);
            }
            Outcome outcome = refresh(connection);
            if (outcome != Outcome.OK) {
                log.warn("connector OAuth refresh did not succeed connection={} outcome={}", connectionId, outcome);
            }
            return new ConnectorOAuthRefresh(outcome, outcome == Outcome.OK ? current.get(connectionId) : null);
        }
    }


    /**
     * 도구를 부르지 않는 연결도 만료 전에 갱신한다(스펙 DECISION17). 10분마다 돌고 검사에서는 {@code -} 로 끄고 직접 부른다.
     *
     * <p>{@code READY} 인 단추 연결 가운데 현재 칸이 없거나 곧 만료되는 것이 대상이다. 재기동 뒤 첫 주기는 메모리가 비어 모두 갱신한다.
     * 한 연결의 실패는 경고로 남기고 다음 연결로 간다.
     */
    @Scheduled(cron = "${assistant.connector.oauth.refresh-cron}")
    public void refreshDue() {
        for (ConnectorConnection connection : connections.findByStatus(ConnectionStatus.READY)) {
            try {
                if (connection.fields().auth().usesOAuth()) {
                    refresh(connection.id(), AHEAD);
                }
            } catch (RuntimeException ex) {
                log.warn("connector OAuth scheduled refresh failed connection={} exception={}",
                    connection.id(), ex.getClass().getSimpleName());
            }
        }
    }


    private Outcome refresh(ConnectorConnection connection) {
        final ConnectorManifest manifest;
        final ConnectorOAuthApp app;
        final Token token;
        try {
            manifest = ConnectorManifests.find(connectors, connection.connectorId()).orElse(null);
            app = manifest == null ? null : apps.app(manifest).orElse(null);
            if (app == null) {
                // 카탈로그에서 빠졌거나 관리자가 앱 값을 비웠다. 사용자 연결의 탓이 아니라 상태를 두고 이 호출만 실패시킨다.
                return Outcome.UNAVAILABLE;
            }
            token = oauth.refresh(connection.vault(), connection.connectorId(), app.credentials());
        } catch (RuntimeException ex) {
            log.warn("connector OAuth refresh failed connection={} exception={}", connection.id(), ex.getClass().getSimpleName());
            return Outcome.UNAVAILABLE;
        }
        switch (token.outcome()) {
            case OK -> current.put(connection.id(), new ConnectorOAuthToken(token.accessToken(), token.expiresAt()));
            case GRANT_REVOKED -> {
                current.remove(connection.id());
                alerts.reconnectRequired(connection, manifest.title());
            }
            case APP_REJECTED -> alerts.appRejected(connection.userId(), app.secretName(), manifest.title());
            // 그 사이 붙여 넣기로 바뀌어 OAuth 파일이 없다. 메모리 칸만 지운다.
            case MISSING -> current.remove(connection.id());
            default -> {
                return Outcome.UNAVAILABLE;
            }
        }
        return token.outcome();
    }


    private static String pendingKey(long connectionId, String nonce) {
        return connectionId + "." + nonce;
    }
}
