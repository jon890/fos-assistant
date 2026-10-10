package com.bifos.assistant.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.connector.application.execution.ConnectorExecutionClaimCodec;
import com.bifos.assistant.connector.application.execution.ConnectorExecutionTicket;
import com.bifos.assistant.hermes.HermesProperties;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.testsupport.ConnectorExecutionClaimTestSupport;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** 제품 서명 helper 대신 Python과 OpenSSL로 각각 계산해 대조한 고정 HMAC 벡터를 소비한다. */
class ConnectorExecutionTicketVectorTest extends ConnectorExecutionClaimTestSupport {
    private static final JsonNode VECTOR = vector();

    @Autowired
    ConnectorExecutionTicket tickets;

    @Autowired
    ConnectorExecutionClaimCodec codec;

    @Test
    @DisplayName("독립 계산한 14개 키의 고정 HMAC ticket을 실제 codec과 인증 함수가 받아들인다")
    void authenticatesIndependentFixedVector() {
        String payload = VECTOR.get("payload").stringValue();
        String encoded = VECTOR.get("payloadBase64Url").stringValue();
        assertThat(Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(encoded);
        assertThat(JSON.readTree(payload).size()).isEqualTo(14);
        assertThat(VECTOR.get("token").stringValue()).isEqualTo(TOKEN);
        assertThat(VECTOR.get("signatureBase64Url").stringValue())
                .isEqualTo("-eV0tgxaRH9thxpz1lyj6T0ZMZM9r9KyrFUqdYh4aJg");
        var expected = codec.decodePayload(payload.getBytes(StandardCharsets.UTF_8));
        var actual = tickets.authenticate(ticket("signatureBase64Url"));
        assertThat(actual).isEqualTo(expected);
        assertThat(actual.ticketId()).isEqualTo(UUID.fromString("11111111-1111-4111-8111-111111111111"));
        assertThat(actual.issuedAt()).isEqualTo(Instant.parse("2026-10-10T00:00:02.123456Z"));
        assertThat(actual.expiresAt()).isEqualTo(Instant.parse("2026-10-10T00:01:02.123456Z"));
        assertThat(new String(codec.encodePayload(actual), StandardCharsets.UTF_8))
                .isEqualTo(payload);
    }

    @ParameterizedTest
    @ValueSource(strings = {"wrongTokenSignature", "wrongKeyDomainSignature", "wrongSignDomainSignature"})
    @DisplayName("동일 payload라도 다른 토큰·키 유도 도메인·서명 도메인의 고정 서명은 거절한다")
    void rejectsIndependentWrongSignatures(String signature) {
        assertThatThrownBy(() -> tickets.authenticate(ticket(signature)))
                .isInstanceOf(ApiException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.UNAUTHENTICATED);
    }

    @Test
    @DisplayName("정상 고정 ticket도 다른 dashboardToken 설정과 서명 한 바이트 변조에서는 거절한다")
    void rejectsChangedTokenAndTamperedSignature() {
        var properties = new HermesProperties(null, null, "another-public-test-token", null, null, null, null, null);
        // authenticate는 DB나 HTTP에 접근하지 않으므로 그 의존성을 전달하지 않는다.
        var other = new ConnectorExecutionTicket(null, codec, null, null, null, null, clock, properties);
        assertThatThrownBy(() -> other.authenticate(ticket("signatureBase64Url")))
                .isInstanceOf(ApiException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.UNAUTHENTICATED);
        byte[] signature =
                Base64.getUrlDecoder().decode(VECTOR.get("signatureBase64Url").stringValue());
        signature[0] ^= 1;
        String tampered = VECTOR.get("payloadBase64Url").stringValue() + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
        assertThatThrownBy(() -> tickets.authenticate(tampered))
                .isInstanceOf(ApiException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.UNAUTHENTICATED);
    }

    private static String ticket(String signature) {
        return VECTOR.get("payloadBase64Url").stringValue() + "."
                + VECTOR.get(signature).stringValue();
    }

    private static JsonNode vector() {
        try {
            return JSON.readTree(Files.readString(Path.of("../test/resources/financial-ticket-v1.json")));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
