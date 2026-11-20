package com.bifos.assistant.connector.oauth.application;

import com.bifos.assistant.browser.config.BrowserProperties;
import com.bifos.assistant.connector.oauth.application.dto.ConnectorOAuthMark;
import com.bifos.assistant.connector.oauth.application.dto.ConnectorOAuthState;
import com.bifos.assistant.crypto.domain.ServerSecrets;
import com.bifos.assistant.shared.config.LiveProperties;
import com.bifos.assistant.shared.util.Sha256;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 단추 연결의 상태 값과 토큰 통로 표식을 서명하고 확인한다(ADR-20261120 / connector-oauth).
 *
 * <p>서명 비밀은 서버 비밀값 {@value #SECRET_NAME} 줄이고 처음 쓸 때 만든다. 표식과 상태 값의 모양은
 * {@code docs/features/connector-oauth/design.md} 의 「Control Plane API」 가 갖는다. 비밀과 서명은 로그와 예외 메시지에 쓰지 않는다.
 */
@Component
@RequiredArgsConstructor
public class ConnectorOAuthSigner {
    /** 서명 비밀을 두는 서버 비밀값의 이름이다. */
    public static final String SECRET_NAME = "connector_oauth_signing";

    /** 시작한 흐름이 돌아올 수 있는 시간이다. */
    public static final Duration STATE_TTL = Duration.ofMinutes(10);

    private static final String HMAC = "HmacSHA256";
    private static final String VERSION = "v1";
    private static final String BROWSER_PATH = "/internal/browser-gateway";
    private static final String TOKEN_PATH = "/internal/connector-token";
    private static final Pattern CURRENT = Pattern.compile("^k([1-9][0-9]{0,17})\\.([0-9a-f]{64})$");
    private static final Pattern PENDING = Pattern.compile("^p([1-9][0-9]{0,17})\\.([A-Za-z0-9_-]{1,64})\\.([0-9a-f]{64})$");
    private static final Pattern STATE = Pattern.compile(
        "^v1\\.([1-9][0-9]{0,17})\\.([a-z0-9][a-z0-9-]{0,63})\\.([1-9][0-9]{0,11})\\.([0-9a-f]{32})\\.([0-9a-f]{64})$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ServerSecrets secrets;
    private final LiveProperties<BrowserProperties> browser;
    private final Clock clock;


    /** 서명 비밀을 쓸 수 있고 통로 주소를 만들 수 있는가. */
    public boolean available() {
        return base().isPresent() && secrets.available();
    }


    /** 본 연결 표식을 붙인 통로 주소다. 통로를 쓸 수 없으면 비어 있다. */
    public Optional<String> currentUrl(long connectionId) {
        return url("k" + connectionId, markText("k", connectionId, null));
    }


    /** 확인 전 흐름의 대기 표식을 붙인 통로 주소다. */
    public Optional<String> pendingUrl(long connectionId, String nonce) {
        return url("p" + connectionId + "." + nonce, markText("p", connectionId, nonce));
    }


    /** 표식의 모양과 서명이 맞을 때만 값을 준다. 어느 까닭으로 거절했는지는 돌려주지 않는다. */
    public Optional<ConnectorOAuthMark> verifyMark(String token) {
        Optional<String> secret = token == null ? Optional.empty() : secret();
        if (secret.isEmpty()) {
            return Optional.empty();
        }
        Matcher current = CURRENT.matcher(token);
        if (current.matches()) {
            long id = Long.parseLong(current.group(1));
            return matches(secret.get(), markText("k", id, null), current.group(2)) ? Optional.of(new ConnectorOAuthMark(id, null)) : Optional.empty();
        }
        Matcher pending = PENDING.matcher(token);
        if (!pending.matches()) {
            return Optional.empty();
        }
        long id = Long.parseLong(pending.group(1));
        String nonce = pending.group(2);
        return matches(secret.get(), markText("p", id, nonce), pending.group(3)) ? Optional.of(new ConnectorOAuthMark(id, nonce)) : Optional.empty();
    }


    /** 새 흐름의 무작위 값이다. 상태 값과 PKCE verifier 가 이 값에서 나온다. */
    public String newRandom() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }


    /** 지금부터 {@link #STATE_TTL} 동안 받는 상태 값이다. 서명 비밀을 쓸 수 없으면 비어 있다. */
    public Optional<String> state(long userId, String connectorId, String random, String redirectUri, String nonce) {
        String expiry = Long.toString(clock.instant().plus(STATE_TTL).getEpochSecond());
        String head = String.join(".", VERSION, Long.toString(userId), connectorId, expiry, random);
        return secret().map(secret -> head + "." + sign(secret, stateText(head, redirectUri, nonce)));
    }


    /** 상태 값의 모양을 읽는다. 만료와 서명은 보지 않는다. */
    public Optional<ConnectorOAuthState> parseState(String state) {
        Matcher matcher = state == null ? null : STATE.matcher(state);
        if (matcher == null || !matcher.matches()) {
            return Optional.empty();
        }
        return Optional.of(new ConnectorOAuthState(
            Long.parseLong(matcher.group(1)), matcher.group(2), Instant.ofEpochSecond(Long.parseLong(matcher.group(3))),
            matcher.group(4), matcher.group(5)));
    }


    /** 만료 전이고 그 흐름의 돌아오는 주소와 {@code nonce} 로 서명한 값인가. */
    public boolean stateMatches(ConnectorOAuthState claim, String redirectUri, String nonce) {
        String head = String.join(".", VERSION, Long.toString(claim.userId()), claim.connectorId(),
            Long.toString(claim.expiresAt().getEpochSecond()), claim.random());
        return claim.expiresAt().isAfter(clock.instant())
            && secret().filter(secret -> matches(secret, stateText(head, redirectUri, nonce), claim.signature())).isPresent();
    }


    /** 저장하지 않고 무작위 값에서 다시 만드는 PKCE verifier 다. */
    public Optional<String> pkceVerifier(String random) {
        return secret().map(secret -> sign(secret, String.join("\n", VERSION, "pkce", random)));
    }


    /** verifier 의 {@code S256} challenge 다. */
    public static String pkceChallenge(String verifier) {
        byte[] digest = Sha256.newDigest().digest(verifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }


    private Optional<String> url(String mark, String text) {
        Optional<String> base = base();
        return base.isEmpty() ? Optional.empty() : secret().map(secret -> base.get() + "/" + mark + "." + sign(secret, text));
    }


    /** 플러그인 프로세스에서 Control Plane 에 닿는 주소는 브라우저 중계 설정 하나다. 그 호스트에 통로 경로를 붙인다. */
    private Optional<String> base() {
        String gateway = browser.current().gatewayBaseUrl();
        if (gateway == null || !gateway.endsWith(BROWSER_PATH)) {
            return Optional.empty();
        }
        return Optional.of(gateway.substring(0, gateway.length() - BROWSER_PATH.length()) + TOKEN_PATH);
    }


    /** 줄이 비어 있으면 한 번 만든다. 두 요청이 함께 만들지 않게 이 객체에서 직렬화한다. */
    private Optional<String> secret() {
        if (!secrets.available()) {
            return Optional.empty();
        }
        Optional<String> stored = secrets.read(SECRET_NAME).filter(value -> !value.isBlank());
        if (stored.isPresent()) {
            return stored;
        }
        synchronized (this) {
            Optional<String> again = secrets.read(SECRET_NAME).filter(value -> !value.isBlank());
            if (again.isPresent()) {
                return again;
            }
            byte[] bytes = new byte[32];
            RANDOM.nextBytes(bytes);
            String created = HexFormat.of().formatHex(bytes);
            secrets.write(SECRET_NAME, created, Map.of(), null);
            return Optional.of(created);
        }
    }


    private static String markText(String kind, long connectionId, String nonce) {
        String head = String.join("\n", VERSION, kind, Long.toString(connectionId));
        return nonce == null ? head : head + "\n" + nonce;
    }


    private static String stateText(String head, String redirectUri, String nonce) {
        return String.join("\n", "state", head, redirectUri, nonce);
    }


    private static boolean matches(String secret, String text, String signature) {
        byte[] expected = sign(secret, text).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, signature.getBytes(StandardCharsets.US_ASCII));
    }


    private static String sign(String secret, String text) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC));
            return HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HmacSHA256 is unavailable", ex);
        }
    }
}
