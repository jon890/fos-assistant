package com.bifos.assistant.connector.application.execution;

import com.bifos.assistant.connector.application.model.ConnectorExecutionClaimInput;
import com.bifos.assistant.connector.application.model.ConnectorExecutionTicketPayload;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonToken;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 원문의 바이트 경계와 JSON token을 확인한 뒤 불변 입력으로 옮긴다. */
@Component
public class ConnectorExecutionClaimCodec {
    public static final int REQUEST_LIMIT = 8 * 1024;
    private static final Set<String> PAYLOAD_KEYS = Set.of(
            "v",
            "ticketId",
            "actionId",
            "userId",
            "agentId",
            "connectionId",
            "bindingId",
            "profile",
            "connectorId",
            "tool",
            "argsSha256",
            "scopeSha256",
            "issuedAt",
            "expiresAt");
    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .streamReadConstraints(
                            StreamReadConstraints.builder().maxNestingDepth(5).build())
                    .build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    public ConnectorExecutionClaimInput decodeRequest(byte[] raw) {
        JsonNode node = parse(raw, REQUEST_LIMIT, true);
        if (!node.has("ticket")) {
            throw error(ErrorCode.UNAUTHENTICATED);
        }
        keys(node, Set.of("ticket", "tool", "argsSha256", "scope"));
        String ticket = text(node.get("ticket"), 4096);
        require(ticket.chars().allMatch(c -> c < 128));
        return new ConnectorExecutionClaimInput(
                ticket, text(node.get("tool"), 128), hash(node.get("argsSha256")), scope(node.get("scope")));
    }

    public ConnectorExecutionTicketPayload decodePayload(byte[] raw) {
        JsonNode node = parse(raw, 3 * 1024, false);
        keys(node, PAYLOAD_KEYS);
        require(number(node.get("v")) == 1);
        return new ConnectorExecutionTicketPayload(
                1,
                uuid(node.get("ticketId")),
                uuid(node.get("actionId")),
                number(node.get("userId")),
                number(node.get("agentId")),
                number(node.get("connectionId")),
                number(node.get("bindingId")),
                text(node.get("profile"), 64),
                text(node.get("connectorId"), 64),
                text(node.get("tool"), 128),
                hash(node.get("argsSha256")),
                hash(node.get("scopeSha256")),
                instant(node.get("issuedAt")),
                instant(node.get("expiresAt")));
    }

    public byte[] encodePayload(ConnectorExecutionTicketPayload payload) {
        byte[] raw = JSON.writeValueAsBytes(payload);
        decodePayload(raw);
        return raw;
    }

    public Map<String, String> readScope(String raw) {
        return scope(parse(raw.getBytes(StandardCharsets.UTF_8), 2 * 1024, false));
    }

    private static JsonNode parse(byte[] raw, int limit, boolean request) {
        require(raw != null && raw.length <= limit);
        try {
            StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw));
            // tree로 바꾸면 수의 지수 표현과 scope의 수신 바이트 길이를 잃으므로 token을 먼저 검사한다.
            try (var parser = JSON.createParser(raw)) {
                int depth = 0;
                long scopeStart = -1;
                int scopeDepth = -1;
                String field = null;
                JsonToken token;
                while ((token = parser.nextToken()) != null) {
                    if (token == JsonToken.PROPERTY_NAME) {
                        field = parser.currentName();
                    } else {
                        if (request && depth == 1 && "scope".equals(field) && scopeStart < 0) {
                            require(token == JsonToken.START_OBJECT);
                            scopeStart = parser.currentTokenLocation().getByteOffset();
                            scopeDepth = depth + 1;
                        }
                        if (token == JsonToken.VALUE_NUMBER_INT || token == JsonToken.VALUE_NUMBER_FLOAT) {
                            require(parser.getString().matches("[1-9][0-9]*"));
                        }
                        if (token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY) {
                            depth++;
                        } else if (token == JsonToken.END_OBJECT || token == JsonToken.END_ARRAY) {
                            if (depth == scopeDepth) {
                                require(parser.currentLocation().getByteOffset() - scopeStart <= 2 * 1024);
                            }
                            depth--;
                        }
                        field = null;
                    }
                }
            }
            JsonNode node = JSON.readTree(raw);
            require(node != null && node.isObject());
            return node;
        } catch (JacksonException | CharacterCodingException ignored) {
            throw error(ErrorCode.VALIDATION_FAILED);
        }
    }

    private static Map<String, String> scope(JsonNode node) {
        require(node != null && node.isObject() && !node.isEmpty() && node.size() <= 8);
        var values = new LinkedHashMap<String, String>();
        for (var entry : node.properties()) {
            require(entry.getKey().matches("[A-Za-z][A-Za-z0-9_]{0,63}"));
            String value = text(entry.getValue(), 128);
            require(value.getBytes(StandardCharsets.UTF_8).length <= 128);
            values.put(entry.getKey(), value);
        }
        return Map.copyOf(values);
    }

    private static long number(JsonNode node) {
        require(node != null && node.isIntegralNumber() && node.canConvertToLong());
        long value = node.longValue();
        require(value > 0);
        return value;
    }

    private static UUID uuid(JsonNode node) {
        String value = text(node, 36);
        try {
            UUID uuid = UUID.fromString(value);
            require(uuid.toString().equals(value));
            return uuid;
        } catch (IllegalArgumentException ignored) {
            throw error(ErrorCode.VALIDATION_FAILED);
        }
    }

    private static Instant instant(JsonNode node) {
        String value = text(node, 40);
        try {
            Instant instant = Instant.parse(value);
            require(instant.toString().equals(value) && instant.getNano() % 1000 == 0);
            return instant;
        } catch (DateTimeException ignored) {
            throw error(ErrorCode.VALIDATION_FAILED);
        }
    }

    private static String hash(JsonNode node) {
        String value = text(node, 64);
        require(value.matches("[0-9a-f]{64}"));
        return value;
    }

    private static String text(JsonNode node, int maximum) {
        require(node != null && node.isString());
        String value = node.stringValue();
        require(!value.isEmpty()
                && value.length() <= maximum
                && StandardCharsets.UTF_8.newEncoder().canEncode(value)
                && value.chars().noneMatch(Character::isISOControl));
        return value;
    }

    private static void keys(JsonNode node, Set<String> names) {
        require(node.propertyNames().equals(names));
    }

    private static void require(boolean condition) {
        if (!condition) {
            throw error(ErrorCode.VALIDATION_FAILED);
        }
    }

    private static ApiException error(ErrorCode code) {
        return new ApiException(code, "financial execution request rejected");
    }
}
