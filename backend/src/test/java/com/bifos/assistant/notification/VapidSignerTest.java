package com.bifos.assistant.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.notification.config.PushProperties;
import com.bifos.assistant.notification.infra.VapidKeys;
import com.bifos.assistant.notification.infra.VapidSigner;
import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** VAPID 머리의 모양과 서명을 본다(RFC 8292, ADR-20261120 / web-push). 키는 검사 전용 파일이다. */
class VapidSignerTest {

    private static final String PUBLIC_KEY = "BJLSTJcZ6Dt9L9GyQVxTOYazbfg-9vZjgMEnL_1gq-El1cIpTfFKBUd77zAwz97o3a9KYJ8LUYTHPIEnhbAuE9Y";
    private static final Instant NOW = Instant.parse("2026-11-20T00:00:00Z");

    private final VapidKeys keys = new VapidKeys(new PushProperties(
        PUBLIC_KEY, "src/test/resources/push/test-vapid-private.key", "mailto:test@example.invalid", List.of("fcm.googleapis.com"),
        Duration.ofSeconds(5), Duration.ofSeconds(10), List.of(), Duration.ofSeconds(30), 10));
    private final VapidSigner signer = new VapidSigner(keys);
    private final JsonMapper json = JsonMapper.builder().build();


    @Test
    @DisplayName("머리는 vapid t=JWT, k=공개 키이고 JWT 는 출처, 12시간 뒤 만료, 연락처를 담고 공개 키로 검증된다")
    void authorizationShape() throws Exception {
        String header = signer.authorization("https://fcm.googleapis.com/fcm/send/abc?x=1", NOW);

        assertThat(header).startsWith("vapid t=").endsWith(", k=" + PUBLIC_KEY);
        String token = header.substring("vapid t=".length(), header.indexOf(", k="));
        String[] parts = token.split("\\.");
        assertThat(parts).hasSize(3);
        Base64.Decoder decoder = Base64.getUrlDecoder();
        JsonNode head = json.readTree(decoder.decode(parts[0]));
        JsonNode claims = json.readTree(decoder.decode(parts[1]));
        assertThat(head.path("alg").asString()).isEqualTo("ES256");
        assertThat(claims.path("aud").asString()).isEqualTo("https://fcm.googleapis.com");
        assertThat(claims.path("exp").asLong()).isEqualTo(NOW.plus(Duration.ofHours(12)).getEpochSecond());
        assertThat(claims.path("sub").asString()).isEqualTo("mailto:test@example.invalid");

        Signature verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
        verifier.initVerify(keys.publicKey());
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(verifier.verify(decoder.decode(parts[2]))).isTrue();
    }
}
