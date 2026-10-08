package com.bifos.assistant.browser.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.browser.application.model.BrowserGrant;
import com.bifos.assistant.browser.infra.BrowserProperties;
import com.bifos.assistant.shared.config.LiveProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 중계 접근 표식의 모양, 서명, 만료를 본다. 규칙은 {@code docs/backend/user-browser.md} 의 「접근 표식」 이다. */
class BrowserGatewayTokensTest {

    private static final String BASE = "http://control-plane.example.test/internal/browser-gateway";
    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final String OTHER_SECRET = "fedcba9876543210fedcba9876543210";
    private static final Instant NOW = Instant.parse("2026-10-08T00:00:00Z");

    @Test
    @DisplayName("같은 바인딩 번호는 늘 같은 주소를 받고 그 표식은 바인딩 번호를 준다")
    void issuesStableBindingAddress() {
        BrowserGatewayTokens tokens = tokens(BASE, SECRET, NOW);

        String first = tokens.bindingAddress(42L).orElseThrow();
        String second =
                tokens(BASE, SECRET, NOW.plusSeconds(3600)).bindingAddress(42L).orElseThrow();

        assertThat(first).isEqualTo(second);
        assertThat(first).isEqualTo(BASE + "/b42." + hmac(SECRET, "v1\nbinding\n42"));
        assertThat(tokens.verify(token(first))).contains(new BrowserGrant(BrowserGrant.Kind.BINDING, 42L));
    }

    @Test
    @DisplayName("비밀이 다르면 같은 바인딩이라도 서명이 다르고 다른 비밀의 표식은 확인을 지나지 못한다")
    void signsWithSecret() {
        String mine = token(tokens(BASE, SECRET, NOW).bindingAddress(42L).orElseThrow());
        String other = token(tokens(BASE, OTHER_SECRET, NOW).bindingAddress(42L).orElseThrow());

        assertThat(mine).isNotEqualTo(other);
        assertThat(tokens(BASE, SECRET, NOW).verify(other)).isEmpty();
    }

    @Test
    @DisplayName("서명 한 글자를 바꾸면 확인을 지나지 못한다")
    void rejectsTamperedSignature() {
        BrowserGatewayTokens tokens = tokens(BASE, SECRET, NOW);
        String token = token(tokens.bindingAddress(42L).orElseThrow());
        char last = token.charAt(token.length() - 1);
        String tampered = token.substring(0, token.length() - 1) + (last == '0' ? '1' : '0');

        assertThat(tokens.verify(tampered)).isEmpty();
    }

    @Test
    @DisplayName("앞자리 0 이 붙은 번호와 대문자 16진수 서명은 서명이 맞아도 받지 않는다")
    void rejectsNonCanonicalTokens() {
        BrowserGatewayTokens tokens = tokens(BASE, SECRET, NOW);
        String leadingZero = "b01." + hmac(SECRET, "v1\nbinding\n01");
        String upperCase = "b1." + hmac(SECRET, "v1\nbinding\n1").toUpperCase();

        assertThat(tokens.verify(leadingZero)).isEmpty();
        assertThat(tokens.verify(upperCase)).isEmpty();
        assertThat(tokens.verify("b1." + hmac(SECRET, "v1\nbinding\n1"))).isPresent();
    }

    @Test
    @DisplayName("long 을 넘는 19자리 번호는 서명이 맞아도 받지 않는다")
    void rejectsNumberBeyondLong() {
        String number = "9999999999999999999";

        assertThat(tokens(BASE, SECRET, NOW).verify("b" + number + "." + hmac(SECRET, "v1\nbinding\n" + number)))
                .isEmpty();
    }

    @Test
    @DisplayName("호출 표식은 5분 뒤 만료되고 만료 1초 전에는 사용자 번호를 주고 만료 1초 뒤에는 받지 않는다")
    void expiresCallToken() {
        String address = tokens(BASE, SECRET, NOW).callAddress(7L).orElseThrow();
        long expiry = NOW.plus(Duration.ofMinutes(5)).getEpochSecond();
        String token = token(address);

        assertThat(token).isEqualTo("u7." + expiry + "." + hmac(SECRET, "v1\ncall\n7\n" + expiry));
        assertThat(tokens(BASE, SECRET, Instant.ofEpochSecond(expiry - 1)).verify(token))
                .contains(new BrowserGrant(BrowserGrant.Kind.CALL, 7L));
        assertThat(tokens(BASE, SECRET, Instant.ofEpochSecond(expiry + 1)).verify(token))
                .isEmpty();
    }

    @Test
    @DisplayName("중계 설정 가운데 하나라도 비면 주소를 만들지 않고 맞는 표식도 받지 않는다")
    void staysOffWithoutGatewaySettings() {
        String token = token(tokens(BASE, SECRET, NOW).bindingAddress(42L).orElseThrow());

        for (BrowserGatewayTokens off :
                new BrowserGatewayTokens[] {tokens(null, SECRET, NOW), tokens(BASE, " ", NOW), tokens(null, null, NOW)
                }) {
            assertThat(off.enabled()).isFalse();
            assertThat(off.bindingAddress(42L)).isEmpty();
            assertThat(off.callAddress(7L)).isEmpty();
            assertThat(off.verify(token)).isEmpty();
        }
    }

    private static BrowserGatewayTokens tokens(String baseUrl, String secret, Instant now) {
        BrowserProperties properties = new BrowserProperties(
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                1024,
                null,
                null,
                null,
                2,
                Duration.ofMinutes(10),
                Duration.ofSeconds(30),
                Duration.ofMinutes(30),
                baseUrl,
                secret);
        return new BrowserGatewayTokens(
                LiveProperties.fixed(BrowserProperties.class, properties), Clock.fixed(now, ZoneOffset.UTC));
    }

    private static String token(String address) {
        assertThat(address).startsWith(BASE + "/");
        return address.substring(BASE.length() + 1);
    }

    private static String hmac(String secret, String text) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
