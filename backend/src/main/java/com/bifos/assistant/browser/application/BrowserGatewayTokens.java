package com.bifos.assistant.browser.application;

import com.bifos.assistant.browser.application.model.BrowserGrant;
import com.bifos.assistant.browser.infra.BrowserProperties;
import com.bifos.assistant.shared.config.LiveProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 브라우저 중계의 접근 표식을 만들고 확인한다(ADR-20261008 / browser-gateway-token).
 *
 * <p>표식은 {@code gateway-secret} 로 번호를 HMAC-SHA256 한 값이고 표에 두지 않는다. 같은 바인딩은 늘 같은 표식을 받는다. 모양은
 * {@code docs/backend/user-browser.md} 의 「접근 표식」 이 갖는다. 표식과 서명과 비밀값은 로그와 예외 메시지에 싣지 않는다.
 */
@Component
@RequiredArgsConstructor
public class BrowserGatewayTokens {

    /** 호출 표식이 쓸 수 있는 시간이다. */
    static final Duration CALL_TTL = Duration.ofMinutes(5);

    private static final String HMAC = "HmacSHA256";
    private static final String VERSION_LINE = "v1";
    private static final Pattern BINDING = Pattern.compile("^b([1-9][0-9]{0,18})\\.([0-9a-f]{64})$");
    private static final Pattern CALL = Pattern.compile("^u([1-9][0-9]{0,18})\\.([1-9][0-9]{0,11})\\.([0-9a-f]{64})$");

    private final LiveProperties<BrowserProperties> properties;
    private final Clock clock;

    /** 중계가 켜져 있는가. 두 설정 가운데 하나라도 비면 꺼져 있다. */
    public boolean enabled() {
        return properties.current().gatewayEnabled();
    }

    /** 바인딩 표식을 실은 중계 주소다. 중계가 꺼졌으면 비어 있다. */
    public Optional<String> bindingAddress(long bindingId) {
        requirePositive(bindingId);
        BrowserProperties current = properties.current();
        if (!current.gatewayEnabled()) {
            return Optional.empty();
        }
        String number = Long.toString(bindingId);
        String signature = sign(current.gatewaySecret(), bindingText(number));
        return Optional.of(current.gatewayBaseUrl() + "/b" + number + "." + signature);
    }

    /** 지금부터 5분 동안 쓸 수 있는 호출 표식을 실은 중계 주소다. 중계가 꺼졌으면 비어 있다. */
    public Optional<String> callAddress(long userId) {
        requirePositive(userId);
        BrowserProperties current = properties.current();
        if (!current.gatewayEnabled()) {
            return Optional.empty();
        }
        String number = Long.toString(userId);
        String expiry = Long.toString(clock.instant().plus(CALL_TTL).getEpochSecond());
        String signature = sign(current.gatewaySecret(), callText(number, expiry));
        return Optional.of(current.gatewayBaseUrl() + "/u" + number + "." + expiry + "." + signature);
    }

    /**
     * 표식을 확인한다. 모양과 서명이 맞고, 호출 표식이면 만료 전일 때만 값을 준다.
     *
     * <p>어느 까닭으로 거절했는지는 돌려주지 않는다. 중계가 꺼졌으면 비어 있다.
     */
    public Optional<BrowserGrant> verify(String token) {
        BrowserProperties current = properties.current();
        if (token == null || !current.gatewayEnabled()) {
            return Optional.empty();
        }
        Matcher binding = BINDING.matcher(token);
        if (binding.matches()) {
            String number = binding.group(1);
            return signatureMatches(current.gatewaySecret(), bindingText(number), binding.group(2))
                    ? parse(number).map(id -> new BrowserGrant(BrowserGrant.Kind.BINDING, id))
                    : Optional.empty();
        }
        Matcher call = CALL.matcher(token);
        if (!call.matches()) {
            return Optional.empty();
        }
        String number = call.group(1);
        String expiry = call.group(2);
        if (!signatureMatches(current.gatewaySecret(), callText(number, expiry), call.group(3))) {
            return Optional.empty();
        }
        if (!Instant.ofEpochSecond(Long.parseLong(expiry)).isAfter(clock.instant())) {
            return Optional.empty();
        }
        return parse(number).map(id -> new BrowserGrant(BrowserGrant.Kind.CALL, id));
    }

    private static String bindingText(String number) {
        return String.join("\n", VERSION_LINE, "binding", number);
    }

    private static String callText(String number, String expiry) {
        return String.join("\n", VERSION_LINE, "call", number, expiry);
    }

    /** 고정 시간 비교다. 두 쪽 모두 소문자 16진수 64자다. */
    private static boolean signatureMatches(String secret, String text, String signature) {
        byte[] expected = sign(secret, text).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, signature.getBytes(StandardCharsets.US_ASCII));
    }

    /** 19자리 번호는 {@code long} 을 넘을 수 있다. 넘으면 비어 있다. */
    private static Optional<Long> parse(String number) {
        try {
            return Optional.of(Long.parseLong(number));
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
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

    /** 0 이하의 번호로 만든 표식은 확인을 지나지 못한다. 만들 때 막는다. */
    private static void requirePositive(long id) {
        if (id < 1) {
            throw new IllegalArgumentException("gateway token id must be positive");
        }
    }
}
