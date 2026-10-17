package com.bifos.assistant.connector.financial.presentation;

import com.bifos.assistant.connector.financial.application.ConnectorExecutionSnapshot;
import com.bifos.assistant.connector.financial.application.dto.ConnectorExecutionClaimResult;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** claim HTTP 응답 모양이다. 요청은 원문을 strict codec으로 읽는다. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ConnectorExecutionClaimDtos {
    private static final JsonMapper SUPPORT_JSON = JsonMapper.builder(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(2).build()).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();


    public record ClaimResponse(int v, boolean allowed, UUID ticketId, Instant expiresAt) {
        public static ClaimResponse from(ConnectorExecutionClaimResult result) {
            return new ClaimResponse(result.v(), result.allowed(), result.ticketId(), result.expiresAt());
        }
    }

    /** 소비자가 보낸 타입과 원문을 그대로 응답하기 위한 요청이다. ID와 revision은 문자열이다. */
    public record SupportRequest(int v, String protocol, String bindingId, String connectionId,
        String connectionUpdatedAt, String manifestSha256, String nonce, String profile, String connectorId) {


        public static final int REQUEST_LIMIT = 4096;
        private static final Set<String> KEYS = Set.of("v", "protocol", "bindingId", "connectionId",
            "connectionUpdatedAt", "manifestSha256", "nonce", "profile", "connectorId");

        /** 타입 보정, 중복 키, 추가 키, 뒤따르는 JSON과 비정규 값을 받지 않는다. */
        public static SupportRequest decode(byte[] raw) {
            try {
                require(raw.length <= REQUEST_LIMIT);
                StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw));
                var node = SUPPORT_JSON.readTree(raw);
                require(node != null && node.isObject() && node.propertyNames().equals(KEYS));
                require(node.get("v").isIntegralNumber() && node.get("v").canConvertToInt()
                    && node.get("v").intValue() == 1);
                String protocol = text(node, "protocol", 64);
                require(ConnectorExecutionSnapshot.PROTOCOL.equals(protocol));
                String bindingId = id(node, "bindingId");
                String connectionId = id(node, "connectionId");
                String revision = text(node, "connectionUpdatedAt", 40);
                Instant instant = Instant.parse(revision);
                require(instant.toString().equals(revision) && instant.getNano() % 1000 == 0);
                String sha = text(node, "manifestSha256", 64);
                require(sha.matches("[0-9a-f]{64}"));
                String nonce = text(node, "nonce", 36);
                require(nonce.matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"));
                return new SupportRequest(1, protocol, bindingId, connectionId, revision, sha, nonce,
                    text(node, "profile", 64), text(node, "connectorId", 64));
            } catch (Exception ignored) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "connector support request rejected");
            }
        }


        private static String id(JsonNode node, String name) {
            String value = text(node, name, 19);
            require(value.matches("[1-9][0-9]{0,18}") && Long.parseLong(value) > 0);
            return value;
        }


        private static String text(JsonNode node, String name, int limit) {
            JsonNode field = node.get(name);
            require(field.isString());
            String value = field.stringValue();
            require(!value.isBlank() && value.length() <= limit
                && StandardCharsets.UTF_8.newEncoder().canEncode(value)
                && value.chars().noneMatch(Character::isISOControl));
            return value;
        }


        private static void require(boolean condition) {
            if (!condition) {
                throw new IllegalArgumentException("connector support request rejected");
            }
        }
    }

    /** 현재 요청의 guard 칸과 nonce만 돌려준다. 저장하거나 거래 권한으로 쓰지 않는다. */
    public record SupportResponse(int v, String protocol, String bindingId, String connectionId,
        String connectionUpdatedAt, String manifestSha256, String nonce) {
        public static SupportResponse from(SupportRequest request) {
            return new SupportResponse(request.v(), request.protocol(), request.bindingId(), request.connectionId(),
                request.connectionUpdatedAt(), request.manifestSha256(), request.nonce());
        }
    }
}
