package com.bifos.assistant.connector;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.browser.config.BrowserProperties;
import com.bifos.assistant.connector.oauth.application.ConnectorOAuthSigner;
import com.bifos.assistant.connector.oauth.application.dto.ConnectorOAuthMark;
import com.bifos.assistant.connector.oauth.application.dto.ConnectorOAuthState;
import com.bifos.assistant.shared.config.LiveProperties;
import com.bifos.assistant.testsupport.InMemoryServerSecrets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ConnectorOAuthSignerTest {
    private static final Instant NOW = Instant.parse("2026-11-21T00:00:00Z");
    private static final String GATEWAY = "http://cp.example.test/internal/browser-gateway";
    private static final String TOKEN_BASE = "http://cp.example.test/internal/connector-token/";
    private static final String REDIRECT = "https://web.example.test/connections/oauth/callback";
    private static final String NONCE = "nonce-for-test_1";

    private final InMemoryServerSecrets secrets = InMemoryServerSecrets.with("unrelated", "value");


    @Test
    @DisplayName("본 연결 표식과 대기 표식은 만든 번호와 nonce 로만 확인된다")
    void marksVerifyOnlyForTheSignedNumberAndNonce() {
        ConnectorOAuthSigner signer = signer(GATEWAY, NOW);
        String current = signer.currentUrl(7).orElseThrow();
        String pending = signer.pendingUrl(7, NONCE).orElseThrow();
        assertThat(current).startsWith(TOKEN_BASE + "k7.");
        assertThat(pending).startsWith(TOKEN_BASE + "p7." + NONCE + ".");

        String currentMark = current.substring(TOKEN_BASE.length());
        String pendingMark = pending.substring(TOKEN_BASE.length());
        assertThat(signer.verifyMark(currentMark)).contains(new ConnectorOAuthMark(7, null));
        assertThat(signer.verifyMark(pendingMark)).contains(new ConnectorOAuthMark(7, NONCE));

        assertThat(signer.verifyMark(currentMark.replaceFirst("^k7", "k8"))).as("번호를 바꾼 표식").isEmpty();
        assertThat(signer.verifyMark(pendingMark.replace(NONCE, "other-nonce"))).as("nonce 를 바꾼 표식").isEmpty();
        assertThat(signer.verifyMark("p7." + NONCE + "." + currentMark.substring(3))).as("종류를 바꾼 표식").isEmpty();
        assertThat(signer.verifyMark("k7." + "0".repeat(64))).as("위조한 서명").isEmpty();
        assertThat(signer.verifyMark("k7")).isEmpty();
        assertThat(signer.verifyMark(null)).isEmpty();
    }


    @Test
    @DisplayName("서명 비밀은 처음 쓸 때 한 번 만들고 다른 비밀의 표식은 받지 않는다")
    void createsSigningSecretOnceAndRejectsMarksOfAnotherSecret() {
        ConnectorOAuthSigner signer = signer(GATEWAY, NOW);
        assertThat(secrets.read(ConnectorOAuthSigner.SECRET_NAME)).isEmpty();
        String mark = signer.currentUrl(7).orElseThrow().substring(TOKEN_BASE.length());
        String created = secrets.read(ConnectorOAuthSigner.SECRET_NAME).orElseThrow();
        assertThat(created).matches("[0-9a-f]{64}");
        assertThat(signer.currentUrl(7).orElseThrow()).endsWith(mark);
        assertThat(secrets.read(ConnectorOAuthSigner.SECRET_NAME)).contains(created);

        secrets.write(ConnectorOAuthSigner.SECRET_NAME, "f".repeat(64), Map.of(), null);
        assertThat(signer.verifyMark(mark)).isEmpty();
    }


    @Test
    @DisplayName("중계 주소가 없으면 통로 주소를 만들지 않는다")
    void buildsNoPassageAddressWithoutGatewayAddress() {
        ConnectorOAuthSigner signer = signer("", NOW);
        assertThat(signer.available()).isFalse();
        assertThat(signer.currentUrl(7)).isEmpty();
        assertThat(signer.pendingUrl(7, NONCE)).isEmpty();
        assertThat(signer(GATEWAY, NOW).available()).isTrue();
    }


    @Test
    @DisplayName("상태 값은 만료 전에 같은 돌아오는 주소와 nonce 로만 맞는다")
    void stateMatchesOnlyBeforeExpiryWithSameRedirectAndNonce() {
        ConnectorOAuthSigner signer = signer(GATEWAY, NOW);
        String random = signer.newRandom();
        String state = signer.state(3, "demo-notes", random, REDIRECT, NONCE).orElseThrow();
        ConnectorOAuthState claim = signer.parseState(state).orElseThrow();
        assertThat(claim.userId()).isEqualTo(3);
        assertThat(claim.connectorId()).isEqualTo("demo-notes");
        assertThat(claim.random()).isEqualTo(random);
        assertThat(claim.expiresAt()).isEqualTo(NOW.plus(ConnectorOAuthSigner.STATE_TTL));

        assertThat(signer.stateMatches(claim, REDIRECT, NONCE)).isTrue();
        assertThat(signer.stateMatches(claim, REDIRECT + "x", NONCE)).as("다른 돌아오는 주소").isFalse();
        assertThat(signer.stateMatches(claim, REDIRECT, "other")).as("다른 nonce").isFalse();
        ConnectorOAuthState otherUser = signer.parseState(state.replaceFirst("^v1\\.3\\.", "v1.4.")).orElseThrow();
        assertThat(signer.stateMatches(otherUser, REDIRECT, NONCE)).as("사용자 번호를 바꾼 값").isFalse();
        ConnectorOAuthSigner later = signer(GATEWAY, NOW.plus(ConnectorOAuthSigner.STATE_TTL));
        assertThat(later.stateMatches(claim, REDIRECT, NONCE)).as("만료된 값").isFalse();

        assertThat(signer.parseState("v1.3.demo-notes")).isEmpty();
        assertThat(signer.parseState(null)).isEmpty();
    }


    @Test
    @DisplayName("PKCE verifier 는 같은 무작위 값에서 같은 값이 나오고 challenge 는 S256 이다")
    void derivesPkceVerifierFromRandom() {
        ConnectorOAuthSigner signer = signer(GATEWAY, NOW);
        String verifier = signer.pkceVerifier("a".repeat(32)).orElseThrow();
        assertThat(verifier).hasSize(64).isEqualTo(signer.pkceVerifier("a".repeat(32)).orElseThrow());
        assertThat(signer.pkceVerifier("b".repeat(32)).orElseThrow()).isNotEqualTo(verifier);
        // RFC 7636 부록 B 의 예시다.
        assertThat(ConnectorOAuthSigner.pkceChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
            .isEqualTo("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM");
    }


    private ConnectorOAuthSigner signer(String gateway, Instant now) {
        BrowserProperties properties = new BrowserProperties(
            false, null, null, null, null, null, null, null, 0, null, null, null, 1,
            Duration.ofMinutes(1), Duration.ofMinutes(1), Duration.ofMinutes(1), Duration.ofMinutes(1), gateway, null);
        return new ConnectorOAuthSigner(
            secrets, LiveProperties.fixed(BrowserProperties.class, properties), Clock.fixed(now, ZoneOffset.UTC));
    }
}
