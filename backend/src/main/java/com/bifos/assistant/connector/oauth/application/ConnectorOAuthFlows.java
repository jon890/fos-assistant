package com.bifos.assistant.connector.oauth.application;

import com.bifos.assistant.connector.connection.application.ConnectorBindingService;
import com.bifos.assistant.connector.connection.application.ConnectorCallLimiter;
import com.bifos.assistant.connector.connection.application.ConnectorConnectionService;
import com.bifos.assistant.connector.connection.application.ConnectorManifests;
import com.bifos.assistant.connector.connection.application.ConnectorVerifier;
import com.bifos.assistant.connector.connection.application.dto.ConnectorVerified;
import com.bifos.assistant.connector.connection.application.dto.ConnectorOperationFailure;
import com.bifos.assistant.connector.connection.domain.ConnectionAuth;
import com.bifos.assistant.connector.connection.domain.ConnectionFields;
import com.bifos.assistant.connector.connection.domain.ConnectorConnection;
import com.bifos.assistant.connector.connection.infra.ConnectorConnectionRepository;
import com.bifos.assistant.connector.oauth.application.dto.ConnectorOAuthApp;
import com.bifos.assistant.connector.oauth.application.dto.ConnectorOAuthCompletion;
import com.bifos.assistant.connector.oauth.application.dto.ConnectorOAuthCompletion.Outcome;
import com.bifos.assistant.connector.oauth.application.dto.ConnectorOAuthState;
import com.bifos.assistant.connector.oauth.application.dto.ConnectorOAuthToken;
import com.bifos.assistant.hermes.application.dto.ConnectorManifest;
import com.bifos.assistant.hermes.application.dto.ConnectorOAuth;
import com.bifos.assistant.hermes.application.dto.ConnectorOAuth.Authorization;
import com.bifos.assistant.hermes.application.dto.ConnectorOAuth.Exchange;
import com.bifos.assistant.hermes.application.dto.ConnectorOAuth.Mode;
import com.bifos.assistant.hermes.application.dto.ConnectorOAuth.Token;
import com.bifos.assistant.hermes.application.dto.ConnectorOAuth.VaultAccess;
import com.bifos.assistant.hermes.application.port.ConnectorOAuthFlowConflict;
import com.bifos.assistant.hermes.application.port.HermesConnectorClient;
import com.bifos.assistant.hermes.application.port.HermesConnectorOAuthClient;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 단추 연결을 시작하고, 서비스 화면에서 돌아온 요청으로 연결을 만든다(스펙 REQ4, REQ5, REQ11).
 *
 * <p>순서는 {@code docs/features/connector-oauth/design.md} 의 「흐름」 과 「실패와 일관성」 이 갖는다. 대시보드 commit 전에는 본 자리의
 * 파일과 현재 칸이 그대로라 실패가 기존 연결을 깨지 않는다. 코드, 토큰, 앱 자격은 로그와 예외 메시지에 쓰지 않는다.
 *
 * <p>시작한 흐름은 상태 값의 무작위 값을 key 로 메모리에 10분 둔다(서버 한 대 전제). 꺼내 쓰면 지워 한 번만 받는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConnectorOAuthFlows {
    private static final int MAX_REDIRECT_LENGTH = 500;

    private final ConnectorConnectionService connectionService;
    private final ConnectorBindingService bindings;
    private final ConnectorConnectionRepository connections;
    private final ConnectorCallLimiter limiter;
    private final HermesConnectorClient connectors;
    private final HermesConnectorOAuthClient oauth;
    private final ConnectorVerifier verifier;
    private final ConnectorOAuthApps apps;
    private final ConnectorOAuthSigner signer;
    private final ConnectorOAuthTokens tokens;
    private final ConnectorOAuthAlerts alerts;
    /** Spring Boot 가 transaction manager 로 만든 기본 설정이다. */
    private final TransactionTemplate transactions;
    private final Clock clock;

    private final Map<String, Flow> flows = new ConcurrentHashMap<>();


    /**
     * 시작한 흐름 하나다.
     *
     * @param fields 연결이 만들어지면 저장할 단추 밖 칸 값과 연결 방식
     * @param pkce 로그인 주소에 challenge 를 넣었는가. 넣었으면 교환에 verifier 를 보낸다
     */
    private record Flow(long connectionId, String nonce, ConnectionFields fields, String redirectUri, boolean pkce, Instant expiresAt) {
    }


    /**
     * 단추 밖 칸 값을 대기 보관 파일에 두고 서비스의 로그인 주소를 만든다.
     *
     * @param redirectUri 웹이 자기 공개 주소로 만든 돌아오는 주소. 상태 값의 서명에 들어가 바꿔치기를 막는다
     * @return 사용자를 보낼 로그인 주소
     */
    public String start(CurrentUser user, String connectorId, String redirectUri, Map<String, String> values) {
        return limiter.call(user.id(), () -> {
            ConnectorManifest manifest = ConnectorManifests.find(connectors, connectorId)
                .filter(found -> found.oauth() != null)
                .orElseThrow(() -> new ApiException(ErrorCode.CONNECTOR_NOT_FOUND, "no such connector"));
            ConnectorOAuthApp app = apps.app(manifest).filter(found -> signer.available()).orElseThrow(ConnectorOAuthFlows::notConfigured);
            requireRedirect(redirectUri);
            ConnectionAuth auth = new ConnectionAuth(manifest.oauth().provider(), false);
            Map<String, String> accepted = verifier.oauthValues(manifest, values, auth);
            ConnectorConnection connection = transactions.execute(status -> {
                bindings.lockOwner(user.id());
                Instant now = clock.instant();
                return connections.findByUserIdAndConnectorId(user.id(), connectorId)
                    .orElseGet(() -> connections.save(ConnectorConnection.reserved(user.id(), connectorId, now)));
            });
            try {
                String nonce = oauth.begin(connection.vault(), connectorId, accepted);
                // 플러그인마다 두는 앱(GitHub App)은 설치 화면으로 보낸다. 그 화면은 challenge 를 받을 자리가 없다.
                Flow flow = new Flow(
                    connection.id(), nonce, verifier.oauthFields(manifest, accepted, auth), redirectUri,
                    app.appSlug() == null, clock.instant().plus(ConnectorOAuthSigner.STATE_TTL));
                return authorizeUrl(user.id(), connectorId, app, flow);
            } catch (RuntimeException ex) {
                warn("start", connectorId, ex);
                throw new ApiException(ErrorCode.CONNECTOR_UNAVAILABLE, "the connector is unavailable");
            }
        });
    }


    /**
     * 돌아온 요청을 검사하고 토큰을 받아 확인한 뒤 연결을 만든다. 실패는 예외가 아니라 결과로 답한다.
     *
     * @param code 서비스가 준 코드. 거절했거나 이미 설치된 앱이면 없다
     * @param error 서비스가 준 오류 낱말. 있으면 거절로 본다
     * @param setupAction GitHub App 설치 화면이 준 값. 코드 없이 이 값만 오면 허락 화면으로 한 번 더 보낸다
     */
    public ConnectorOAuthCompletion complete(CurrentUser user, String state, String code, String error, String setupAction) {
        ConnectorOAuthState claim = signer.parseState(state).orElse(null);
        Flow flow = claim == null ? null : flows.get(claim.random());
        // 서명이 맞은 뒤에만 흐름을 지운다. 남의 상태 값을 보낸 요청이 주인의 흐름을 없애지 못한다.
        if (flow == null || claim.userId() != user.id() || !signer.stateMatches(claim, flow.redirectUri(), flow.nonce())
            || !flows.remove(claim.random(), flow)) {
            return ConnectorOAuthCompletion.of(Outcome.INVALID_STATE, null);
        }
        String connectorId = claim.connectorId();
        final ConnectorManifest manifest;
        final ConnectorOAuthApp app;
        try {
            manifest = ConnectorManifests.find(connectors, connectorId).filter(found -> found.oauth() != null).orElse(null);
            app = manifest == null ? null : apps.app(manifest).orElse(null);
        } catch (RuntimeException ex) {
            warn("catalog", connectorId, ex);
            return ConnectorOAuthCompletion.of(Outcome.UNAVAILABLE, connectorId);
        }
        if (app == null) {
            return ConnectorOAuthCompletion.of(Outcome.UNAVAILABLE, connectorId);
        }
        if (hasText(error) || (!hasText(code) && !hasText(setupAction))) {
            return discarded(Outcome.DENIED, flow, connectorId, app, null);
        }
        if (!hasText(code)) {
            try {
                Flow again = new Flow(flow.connectionId(), flow.nonce(), flow.fields(), flow.redirectUri(), true, flow.expiresAt());
                return new ConnectorOAuthCompletion(Outcome.REAUTHORIZE, connectorId, authorizeUrl(user.id(), connectorId, app, again), null);
            } catch (RuntimeException ex) {
                warn("reauthorize", connectorId, ex);
                return discarded(Outcome.UNAVAILABLE, flow, connectorId, app, null);
            }
        }
        return exchanged(user, manifest, app, flow, claim.random(), code);
    }


    private ConnectorOAuthCompletion exchanged(
        CurrentUser user, ConnectorManifest manifest, ConnectorOAuthApp app, Flow flow, String random, String code) {
        String connectorId = manifest.id();
        String vault = ConnectorConnection.vaultOf(flow.connectionId());
        final Token token;
        try {
            token = oauth.exchange(new Exchange(
                vault, connectorId, flow.nonce(), app.credentials(), code,
                flow.pkce() ? signer.pkceVerifier(random).orElseThrow() : null, flow.redirectUri(),
                signer.pendingUrl(flow.connectionId(), flow.nonce()).orElseThrow()));
        } catch (ConnectorOAuthFlowConflict ex) {
            return ConnectorOAuthCompletion.of(Outcome.SUPERSEDED, connectorId);
        } catch (RuntimeException ex) {
            warn("exchange", connectorId, ex);
            return discarded(Outcome.UNAVAILABLE, flow, connectorId, app, null);
        }
        if (token.outcome() != ConnectorOAuth.Outcome.OK) {
            if (token.outcome() == ConnectorOAuth.Outcome.APP_REJECTED) {
                alerts.appRejected(user.id(), app.secretName(), manifest.title());
            }
            return discarded(switch (token.outcome()) {
                case APP_REJECTED -> Outcome.APP_REJECTED;
                case SCOPE_DENIED -> Outcome.SCOPE_DENIED;
                case GRANT_REVOKED -> Outcome.DENIED;
                default -> Outcome.UNAVAILABLE;
            }, flow, connectorId, app, null);
        }
        tokens.putPending(flow.connectionId(), flow.nonce(), new ConnectorOAuthToken(token.accessToken(), token.expiresAt()));
        ConnectorVerified verified = verifier.vault(user.id(), manifest, vault, VaultAccess.PENDING);
        if (verified.failure() != null) {
            ConnectorOAuthCompletion failed = discarded(Outcome.VERIFY_FAILED, flow, connectorId, app, token.accessToken());
            return new ConnectorOAuthCompletion(failed.outcome(), connectorId, null, verified.failure().code().name());
        }
        try {
            connectionService.store(user, manifest, connection -> {
                if (connection.id() != flow.connectionId()) {
                    throw new IllegalStateException("the connection of this flow changed");
                }
                oauth.commit(vault, flow.nonce(), signer.currentUrl(flow.connectionId()).orElseThrow());
                tokens.promote(flow.connectionId(), flow.nonce());
                alerts.reconnected(user.id(), flow.connectionId());
            }, flow.fields(), verified.listed());
        } catch (ConnectorOAuthFlowConflict ex) {
            tokens.dropPending(flow.connectionId(), flow.nonce());
            return ConnectorOAuthCompletion.of(Outcome.SUPERSEDED, connectorId);
        } catch (ConnectorOperationFailure ex) {
            tokens.dropPending(flow.connectionId(), flow.nonce());
            return ConnectorOAuthCompletion.of(Outcome.UNAVAILABLE, connectorId);
        }
        return ConnectorOAuthCompletion.of(Outcome.CONNECTED, connectorId);
    }


    /** 상태 값과 (PKCE 를 쓰면) challenge 를 만들어 흐름을 기억하고 로그인 주소를 받는다. 지난 흐름은 이때 치운다. */
    private String authorizeUrl(long userId, String connectorId, ConnectorOAuthApp app, Flow flow) {
        Instant now = clock.instant();
        flows.values().removeIf(old -> !old.expiresAt().isAfter(now));
        String random = signer.newRandom();
        String state = signer.state(userId, connectorId, random, flow.redirectUri(), flow.nonce()).orElseThrow();
        String challenge = flow.pkce() ? ConnectorOAuthSigner.pkceChallenge(signer.pkceVerifier(random).orElseThrow()) : null;
        String url = oauth.authorizeUrl(new Authorization(
            connectorId, flow.pkce() ? Mode.AUTHORIZE : Mode.INSTALL, app.credentials().clientId(), app.appSlug(),
            flow.redirectUri(), state, challenge));
        flows.put(random, flow);
        return url;
    }


    /** 실패한 흐름의 대기 파일과 대기 칸을 지운다. 지우지 못해도 결과는 바꾸지 않는다. 대기 파일은 10분 뒤 대시보드가 지운다. */
    private ConnectorOAuthCompletion discarded(
        Outcome outcome, Flow flow, String connectorId, ConnectorOAuthApp app, String accessToken) {
        tokens.dropPending(flow.connectionId(), flow.nonce());
        try {
            oauth.discard(ConnectorConnection.vaultOf(flow.connectionId()), connectorId, flow.nonce(), app.credentials(), accessToken);
        } catch (RuntimeException ex) {
            warn("discard", connectorId, ex);
        }
        return ConnectorOAuthCompletion.of(outcome, connectorId);
    }


    private static void requireRedirect(String redirectUri) {
        try {
            URI uri = redirectUri == null || redirectUri.length() > MAX_REDIRECT_LENGTH ? null : URI.create(redirectUri);
            if (uri != null && uri.getHost() != null && uri.getFragment() == null
                && ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))) {
                return;
            }
        } catch (IllegalArgumentException ex) {
            // 아래에서 같은 오류로 끝낸다.
        }
        throw new ApiException(ErrorCode.VALIDATION_FAILED, "connector values are invalid");
    }


    private static ApiException notConfigured() {
        return new ApiException(ErrorCode.CONNECTOR_OAUTH_NOT_CONFIGURED, "the OAuth app of this connector is not configured");
    }


    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }


    /** 단계와 플러그인 번호와 예외 종류만 남긴다. 예외 메시지와 원격 응답에는 코드와 토큰이 섞일 수 있다. */
    private static void warn(String step, String connectorId, RuntimeException ex) {
        log.warn("connector OAuth {} failed at {}: {}", connectorId, step, ex.getClass().getSimpleName());
    }
}
