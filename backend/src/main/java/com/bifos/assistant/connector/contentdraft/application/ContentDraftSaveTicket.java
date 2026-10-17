package com.bifos.assistant.connector.contentdraft.application;

import com.bifos.assistant.connector.contentdraft.application.dto.ContentDraftSaveTicketPayload;
import com.bifos.assistant.hermes.config.HermesProperties;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 원고 저장 worker가 authorize 에 싣는 짧은 범위 ticket 이다. 금융 ticket 과 key·domain·payload 가 모두 다르다.
 *
 * <p>형식은 {@code base64url(payload JSON).base64url(HMAC-SHA256)} 이다. payload 칸과 순서는
 * {@code docs/features/content-draft.md} 의 「저장 ticket과 authorize」 가 갖는다. ticket 과 key 는 DB·로그에 남기지 않는다.
 */
@Component
@RequiredArgsConstructor
public class ContentDraftSaveTicket {
    public static final String PURPOSE = "content-snapshot-save";
    public static final Duration MAX_LIFETIME = Duration.ofSeconds(660);
    static final int MAX_LENGTH = 4096;
    private static final String KEY_LABEL = "fos-content-save-signing-key-v1";
    private static final String DOMAIN = "fos-content-save-v1";
    private static final List<String> KEYS = List.of("v", "purpose", "jobId", "snapshotId", "snapshotHash", "actionId",
        "ownerUserId", "conversationId", "bindingId", "connectionId", "bindingUpdatedAt", "connectionUpdatedAt", "issuedAt", "expiresAt");
    private static final Base64.Encoder ENCODE = Base64.getUrlEncoder().withoutPadding();
    private static final JsonMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private final HermesProperties properties;
    private final Clock clock;


    /** 서명한 ticket 을 만든다. 기한은 발급 뒤 최대 660초와 원고 기한 가운데 이른 쪽이다. */
    public String issue(ContentDraftSaveTicketPayload unsigned, Instant contentExpiresAt) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant expiry = now.plus(MAX_LIFETIME);
        if (contentExpiresAt.isBefore(expiry)) {
            expiry = contentExpiresAt.truncatedTo(ChronoUnit.MICROS);
        }
        if (!expiry.isAfter(now)) {
            throw rejected();
        }
        var payload = new ContentDraftSaveTicketPayload(unsigned.jobId(), unsigned.snapshotId(), unsigned.snapshotHash(),
            unsigned.actionId(), unsigned.ownerUserId(), unsigned.conversationId(), unsigned.bindingId(), unsigned.connectionId(),
            micros(unsigned.bindingUpdatedAt()), micros(unsigned.connectionUpdatedAt()), now, expiry);
        String body = ENCODE.encodeToString(encode(payload));
        String ticket = body + "." + ENCODE.encodeToString(signature(body));
        if (ticket.length() > MAX_LENGTH) {
            throw rejected();
        }
        return ticket;
    }


    /** 서명과 정규형, 기한을 확인한다. 서명이 틀리면 payload 를 읽지 않는다. */
    public ContentDraftSaveTicketPayload authenticate(String ticket) {
        if (ticket == null || ticket.isEmpty() || ticket.length() > MAX_LENGTH
            || !ticket.matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")) {
            throw unauthenticated();
        }
        String[] parts = ticket.split("\\.");
        byte[] raw = decode(parts[0]);
        byte[] signature = decode(parts[1]);
        if (signature.length != 32 || !MessageDigest.isEqual(signature, signature(parts[0]))) {
            throw unauthenticated();
        }
        ContentDraftSaveTicketPayload payload;
        try {
            payload = decodePayload(raw);
        } catch (RuntimeException ignored) {
            throw unauthenticated();
        }
        Instant now = clock.instant();
        if (now.isBefore(payload.issuedAt()) || !now.isBefore(payload.expiresAt())
            || payload.expiresAt().isAfter(payload.issuedAt().plus(MAX_LIFETIME))) {
            throw unauthenticated();
        }
        return payload;
    }


    static byte[] encode(ContentDraftSaveTicketPayload payload) {
        var node = JSON.createObjectNode().put("v", 1).put("purpose", PURPOSE)
            .put("jobId", payload.jobId().toString()).put("snapshotId", payload.snapshotId().toString())
            .put("snapshotHash", payload.snapshotHash()).put("actionId", payload.actionId().toString())
            .put("ownerUserId", payload.ownerUserId().toString()).put("conversationId", payload.conversationId().toString())
            .put("bindingId", payload.bindingId().toString()).put("connectionId", payload.connectionId().toString())
            .put("bindingUpdatedAt", payload.bindingUpdatedAt().toString())
            .put("connectionUpdatedAt", payload.connectionUpdatedAt().toString())
            .put("issuedAt", payload.issuedAt().toString()).put("expiresAt", payload.expiresAt().toString());
        return JSON.writeValueAsBytes(node);
    }


    private static ContentDraftSaveTicketPayload decodePayload(byte[] raw) {
        JsonNode node = JSON.readTree(raw);
        require(node.isObject() && List.copyOf(node.propertyNames()).equals(KEYS));
        require(node.get("v").isInt() && node.get("v").intValue() == 1 && PURPOSE.equals(text(node, "purpose")));
        require(text(node, "snapshotHash").matches("[0-9a-f]{64}"));
        var payload = new ContentDraftSaveTicketPayload(uuid(node, "jobId"), uuid(node, "snapshotId"), text(node, "snapshotHash"),
            uuid(node, "actionId"), id(node, "ownerUserId"), id(node, "conversationId"), id(node, "bindingId"), id(node, "connectionId"),
            instant(node, "bindingUpdatedAt"), instant(node, "connectionUpdatedAt"), instant(node, "issuedAt"), instant(node, "expiresAt"));
        // 같은 뜻의 다른 바이트를 받지 않는다. 다시 쓴 바이트가 받은 바이트와 같아야 한다.
        require(MessageDigest.isEqual(encode(payload), raw));
        return payload;
    }


    private byte[] signature(String body) {
        String token = properties.dashboardToken();
        if (token == null || token.isBlank()) {
            throw unauthenticated();
        }
        try {
            byte[] key = hmac(token.getBytes(StandardCharsets.UTF_8), KEY_LABEL.getBytes(StandardCharsets.UTF_8));
            return hmac(key, (DOMAIN + "." + body).getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException ignored) {
            throw unauthenticated();
        }
    }


    private static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }


    private static byte[] decode(String encoded) {
        try {
            byte[] raw = Base64.getUrlDecoder().decode(encoded);
            if (!ENCODE.encodeToString(raw).equals(encoded)) {
                throw unauthenticated();
            }
            return raw;
        } catch (IllegalArgumentException ignored) {
            throw unauthenticated();
        }
    }


    private static String text(JsonNode node, String key) {
        require(node.get(key).isString());
        return node.get(key).stringValue();
    }


    private static UUID uuid(JsonNode node, String key) {
        String value = text(node, key);
        UUID parsed = UUID.fromString(value);
        require(parsed.toString().equals(value));
        return parsed;
    }


    private static Long id(JsonNode node, String key) {
        String value = text(node, key);
        require(value.matches("[1-9][0-9]{0,18}"));
        return Long.valueOf(value);
    }


    private static Instant instant(JsonNode node, String key) {
        Instant parsed = Instant.parse(text(node, key));
        require(parsed.getNano() % 1000 == 0);
        return parsed;
    }


    private static Instant micros(Instant value) {
        return value.truncatedTo(ChronoUnit.MICROS);
    }


    private static void require(boolean condition) {
        if (!condition) {
            throw new IllegalArgumentException("content save ticket rejected");
        }
    }


    private static ApiException unauthenticated() {
        return new ApiException(ErrorCode.UNAUTHENTICATED, "content save authentication required");
    }


    private static ApiException rejected() {
        return new ApiException(ErrorCode.FORBIDDEN, "content save not allowed");
    }
}
