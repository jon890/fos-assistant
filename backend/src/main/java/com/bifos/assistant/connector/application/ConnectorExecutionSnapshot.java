package com.bifos.assistant.connector.application;

import com.bifos.assistant.connector.domain.ConnectorAction;
import com.bifos.assistant.connector.domain.ConnectorActionExecution;
import com.bifos.assistant.connector.domain.ConnectorBinding;
import com.bifos.assistant.connector.domain.type.ActionDecision;
import com.bifos.assistant.connector.domain.type.ActionStatus;
import com.bifos.assistant.connector.domain.type.ToolApproval;
import com.bifos.assistant.connector.domain.type.ToolRisk;
import com.bifos.assistant.crypto.domain.SealedText;
import com.bifos.assistant.crypto.domain.TextCipher;
import com.bifos.assistant.shared.util.Sha256;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 금융 실행 원문과 표시 값을 검증하고 사용자 데이터 key로 보호한다. 서비스와 외부 API는 모른다. */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
@Accessors(fluent = true)
public final class ConnectorExecutionSnapshot {
    public static final String PROTOCOL = "approval-claim-v1";
    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .streamReadConstraints(
                            StreamReadConstraints.builder().maxNestingDepth(5).build())
                    .build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
    private static final Set<String> NORMALIZED = Set.of("symbol", "quantity", "orderAmount", "price", "timeInForce");
    private static final Set<String> SUMMARY = Set.of(("account,symbol,market,currency,side,quantity,orderAmount,price,"
                    + "orderType,timeInForce,operation,orderId,original,normalization")
            .split(","));
    private static final Set<String> ORIGINAL = Set.of(
            "orderId",
            "symbol",
            "market",
            "currency",
            "side",
            "quantity",
            "orderAmount",
            "price",
            "orderType",
            "timeInForce",
            "status",
            "filledQuantity");
    private static final Set<String> EXPECTED = Set.of(
            ("orderId,symbol,currency,side,quantity,orderAmount,price," + "orderType,timeInForce,status,execution")
                    .split(","));
    private static final Set<String> ORDER_VALUES =
            Set.of("symbol", "side", "quantity", "orderAmount", "price", "orderType", "timeInForce");

    @Getter
    private final String executionArgsJson;

    @Getter
    private final String summaryJson;

    @Getter
    private final String scopeJson;

    @Getter
    private final String executionArgsSha256;

    @Getter
    private final String scopeSha256;

    private final JsonNode execution;

    /** 저장과 읽기가 함께 쓰는 실제 소비 함수다. 검증 뒤에도 원문을 다시 직렬화하지 않는다. */
    public static ConnectorExecutionSnapshot validate(
            String modelArgsJson, String executionArgsJson, String summaryJson, String scopeJson, String operation) {
        require(operation != null && Set.of("CREATE", "MODIFY", "CANCEL").contains(operation));
        JsonNode input = object(modelArgsJson, 16 * 1024);
        JsonNode args = object(executionArgsJson, 16 * 1024);
        JsonNode summary = object(summaryJson, 8 * 1024);
        JsonNode scope = object(scopeJson, 2 * 1024);
        require(!scope.isEmpty() && scope.size() <= 8);
        for (var entry : scope.properties()) {
            require(entry.getKey().matches("[A-Za-z][A-Za-z0-9_]{0,63}"));
            text(entry.getValue(), 1, 128);
            require(bytes(entry.getValue().stringValue()) <= 128);
            equal(entry.getValue(), args.get(entry.getKey()));
        }
        keys(summary, SUMMARY);
        display(summary, false);
        require(summary.get("account").isString()
                && summary.get("account").stringValue().matches("[0-9]{4}"));
        equal(summary.get("operation"), JSON.valueToTree(operation));
        validateArgs(input, args, summary, scope, operation);
        normalizations(input, args, summary.get("normalization"));
        return new ConnectorExecutionSnapshot(
                executionArgsJson, summaryJson, scopeJson, Sha256.hex(executionArgsJson), Sha256.hex(scopeJson), args);
    }

    /** 실행 내용만 준비한다. 저장과 금융 정책 판정, 승인, 권한 발급은 호출자의 별도 경계다. */
    public static ConnectorActionExecution capture(
            ConnectorAction action,
            ConnectorBinding binding,
            String operation,
            String scopeFieldsJson,
            String executionArgsJson,
            String summaryJson,
            String scopeJson,
            TextCipher cipher,
            Instant now) {
        require(action != null && binding != null && action.id() != null && binding.id() != null);
        require(action.risk() == ToolRisk.FINANCIAL
                && action.approvalMode() == ToolApproval.ALWAYS
                && action.decision() == ActionDecision.NEEDS_APPROVAL
                && action.status() == ActionStatus.PENDING);
        require(Objects.equals(action.userId(), binding.connection().userId())
                && Objects.equals(action.agentId(), binding.agent().id())
                && Objects.equals(action.connectorId(), binding.connection().connectorId()));
        require(action.toolName() != null && !action.toolName().isBlank());
        require(action.argsJson() != null && Sha256.hex(action.argsJson()).equals(action.argsSha256()));
        ConnectorExecutionSnapshot snapshot =
                validate(action.argsJson(), executionArgsJson, summaryJson, scopeJson, operation);
        declaredScope(
                scopeFieldsJson,
                object(scopeJson, 2 * 1024),
                binding.connection().fields().values());
        String key = snapshot.requestKey(action.userId(), binding.connection().id(), action.toolName());
        String[] plain = {executionArgsJson, summaryJson, scopeJson};
        String[] columns = {"execution_args_json", "summary_json", "scope_json"};
        String[] stored = plain.clone();
        Long keyId = null;
        if (cipher.enabled()) {
            for (int i = 0; i < columns.length; i++) {
                SealedText sealed;
                try {
                    sealed = cipher.seal(action.userId(), aad(action, columns[i]), plain[i])
                            .orElseThrow();
                } catch (RuntimeException ignored) {
                    throw new IllegalStateException("financial snapshot encryption failed");
                }
                require(sealed.keyId() != null && sealed.content() != null);
                if (keyId != null) {
                    require(keyId.equals(sealed.keyId()));
                }
                keyId = sealed.keyId();
                stored[i] = sealed.content();
            }
        }
        return ConnectorActionExecution.stored(
                action,
                binding.connection().id(),
                binding.id(),
                binding.connection().updatedAt(),
                binding.updatedAt(),
                stored[0],
                stored[1],
                stored[2],
                keyId,
                snapshot.executionArgsSha256,
                snapshot.scopeSha256,
                key,
                PROTOCOL,
                now);
    }

    /** 저장된 key 식별자로 읽는 방식을 정하고 원문 해시와 표시 내용을 다시 검증한다. */
    public static ConnectorExecutionSnapshot open(
            ConnectorAction action, ConnectorActionExecution row, TextCipher cipher) {
        require(action != null && row != null && Objects.equals(action.id(), row.actionId()));
        require(PROTOCOL.equals(row.protocol()));
        require(action.argsJson() != null && Sha256.hex(action.argsJson()).equals(action.argsSha256()));
        String args = openColumn(action, row.contentKeyId(), "execution_args_json", row.executionArgsJson(), cipher);
        String summary = openColumn(action, row.contentKeyId(), "summary_json", row.summaryJson(), cipher);
        String scope = openColumn(action, row.contentKeyId(), "scope_json", row.scopeJson(), cipher);
        require(Sha256.hex(args).equals(row.executionArgsSha256())
                && Sha256.hex(scope).equals(row.scopeSha256()));
        JsonNode shown = object(summary, 8 * 1024);
        text(shown.get("operation"), 1, 6);
        ConnectorExecutionSnapshot snapshot = validate(
                action.argsJson(), args, summary, scope, shown.get("operation").stringValue());
        require(snapshot.requestKey(action.userId(), row.connectionId(), action.toolName())
                .equals(row.requestKey()));
        return snapshot;
    }

    /** 중복 의도 키를 한 함수에서 계산한다. decimal은 정확한 문자열로 정규화한다. */
    public String requestKey(Long userId, Long connectionId, String tool) {
        require(userId != null
                && userId > 0
                && connectionId != null
                && connectionId > 0
                && tool != null
                && !tool.isBlank());
        Map<String, String> values = new TreeMap<>();
        for (var entry : execution.properties()) {
            if (Set.of("clientOrderId", "expected_order").contains(entry.getKey())) {
                continue;
            }
            require(entry.getValue().isString());
            String value = entry.getValue().stringValue();
            if (Set.of("quantity", "orderAmount", "price").contains(entry.getKey())) {
                value = new BigDecimal(value).stripTrailingZeros().toPlainString();
            }
            values.put(entry.getKey(), value);
        }
        require(!values.containsKey("userId") && !values.containsKey("connectionId") && !values.containsKey("tool"));
        values.put("userId", userId.toString());
        values.put("connectionId", connectionId.toString());
        values.put("tool", tool);
        StringBuilder body = new StringBuilder(lengthPrefix("fos-financial-intent-v1"));
        values.forEach((name, value) -> body.append(lengthPrefix(name)).append(lengthPrefix(value)));
        return Sha256.hex(body.toString());
    }

    private static void declaredScope(String raw, JsonNode scope, Map<String, String> publicFields) {
        JsonNode fields = parse(raw, 2 * 1024);
        require(fields.isArray() && !fields.isEmpty() && fields.size() <= 8);
        Set<String> args = new HashSet<>();
        Set<String> names = new HashSet<>();
        for (JsonNode item : fields) {
            keys(item, Set.of("arg", "field"));
            text(item.get("arg"), 1, 64);
            text(item.get("field"), 1, 64);
            String arg = item.get("arg").stringValue();
            String field = item.get("field").stringValue();
            require(arg.matches("[A-Za-z][A-Za-z0-9_]{0,63}") && field.matches("[A-Za-z][A-Za-z0-9_]{0,63}"));
            require(args.add(arg) && names.add(field) && publicFields.containsKey(field));
            equal(scope.get(arg), JSON.valueToTree(publicFields.get(field)));
        }
        keys(scope, args);
    }

    private static void validateArgs(
            JsonNode input, JsonNode args, JsonNode summary, JsonNode scope, String operation) {
        Set<String> allowed = new HashSet<>(scope.propertyNames());
        allowed.addAll(Set.of("market", "currency"));
        JsonNode original = summary.get("original");
        boolean create = "CREATE".equals(operation);
        if (create) {
            allowed.addAll(Set.of("symbol", "side", "orderType", "timeInForce", "clientOrderId"));
            require(original.isNull() && summary.get("orderId").isNull());
            clientOrderId(args.get("clientOrderId"));
        } else {
            allowed.addAll(Set.of("orderId", "expected_order"));
            if ("MODIFY".equals(operation)) {
                allowed.add("orderType");
            }
            keys(original, ORIGINAL);
            display(original, true);
            expectedOrder(original, args.get("expected_order"));
            equal(original.get("market"), args.get("market"));
            equal(original.get("currency"), args.get("currency"));
            equal(original.get("orderId"), args.get("orderId"));
            equal(summary.get("orderId"), args.get("orderId"));
            for (String name : ORDER_VALUES) {
                JsonNode value = args.get(name);
                if (value == null && ("CANCEL".equals(operation) || !"price".equals(name))) {
                    equal(summary.get(name), original.get(name));
                }
            }
        }
        if (!"CANCEL".equals(operation)) {
            for (String name : Set.of("quantity", "orderAmount", "price")) {
                if (args.has(name)) {
                    decimal(args.get(name), false);
                    allowed.add(name);
                }
            }
            require(!args.has("quantity") || !args.has("orderAmount"));
            require(create ? args.has("quantity") != args.has("orderAmount") : !args.has("orderAmount"));
            text(args.get("orderType"), 1, 6);
            require(Set.of("LIMIT", "MARKET").contains(args.get("orderType").stringValue()));
            require("LIMIT".equals(args.get("orderType").stringValue()) == args.has("price"));
        }
        keys(args, allowed);
        for (String name : ORDER_VALUES) {
            if (args.has(name)) {
                equal(summary.get(name), args.get(name));
            } else if (create || "price".equals(name) && !"CANCEL".equals(operation)) {
                require(summary.get(name).isNull());
            }
        }
        equal(summary.get("market"), args.get("market"));
        equal(summary.get("currency"), args.get("currency"));
        validateInput(input, operation);
    }

    private static void expectedOrder(JsonNode original, JsonNode expected) {
        keys(expected, EXPECTED);
        keys(expected.get("execution"), Set.of("filledQuantity"));
        for (String name : EXPECTED) {
            if (!"execution".equals(name)) {
                equal(original.get(name), expected.get(name));
            }
        }
        equal(original.get("filledQuantity"), expected.get("execution").get("filledQuantity"));
    }

    private static void clientOrderId(JsonNode node) {
        String value = text(node, 36, 36);
        try {
            require(UUID.fromString(value).toString().equals(value));
        } catch (IllegalArgumentException ignored) {
            throw invalid();
        }
    }

    private static void validateInput(JsonNode input, String operation) {
        Set<String> allowed = new HashSet<>();
        if ("CREATE".equals(operation)) {
            allowed.addAll(Set.of("symbol", "side", "orderType"));
        } else {
            allowed.add("orderId");
            text(input.get("orderId"), 1, 256);
            if ("MODIFY".equals(operation)) {
                allowed.add("orderType");
            }
        }
        if (!"CANCEL".equals(operation)) {
            Set<String> optionals = "CREATE".equals(operation)
                    ? Set.of("quantity", "orderAmount", "price", "timeInForce")
                    : Set.of("quantity", "price");
            for (String name : optionals) {
                if (input.has(name)) {
                    allowed.add(name);
                    if ("timeInForce".equals(name)) {
                        text(input.get(name), 1, 3);
                        require(Set.of("DAY", "CLS", "OPG")
                                .contains(input.get(name).stringValue()));
                    } else {
                        decimal(input.get(name), false);
                    }
                }
            }
            if (input.has("confirmHighValueOrder")) {
                allowed.add("confirmHighValueOrder");
                require(input.get("confirmHighValueOrder").isBoolean()
                        && !input.get("confirmHighValueOrder").booleanValue());
            }
            text(input.get("orderType"), 1, 6);
            require(Set.of("LIMIT", "MARKET").contains(input.get("orderType").stringValue()));
            require("LIMIT".equals(input.get("orderType").stringValue()) == input.has("price"));
            require(!input.has("quantity") || !input.has("orderAmount"));
            require(
                    "CREATE".equals(operation)
                            ? input.has("quantity") != input.has("orderAmount")
                            : !input.has("orderAmount"));
        }
        if ("CREATE".equals(operation)) {
            require(text(input.get("symbol"), 1, 32).matches("[A-Za-z0-9.-]{1,32}"));
            require(Set.of("BUY", "SELL").contains(text(input.get("side"), 1, 4)));
        }
        keys(input, allowed);
    }

    private static void display(JsonNode node, boolean original) {
        require(text(node.get("symbol"), 1, 32).matches("[A-Za-z0-9.-]{1,32}"));
        text(node.get("market"), 1, 16);
        require(text(node.get("currency"), 3, 3).matches("[A-Z]{3}"));
        require(Set.of("BUY", "SELL").contains(text(node.get("side"), 1, 4)));
        require(Set.of("LIMIT", "MARKET").contains(text(node.get("orderType"), 1, 6)));
        require(Set.of("DAY", "CLS", "OPG").contains(text(node.get("timeInForce"), 1, 3)));
        for (String name : Set.of("quantity", "orderAmount", "price")) {
            require(node.get(name) != null);
            if (!node.get(name).isNull()) {
                decimal(node.get(name), false);
            }
        }
        require("LIMIT".equals(node.get("orderType").stringValue())
                != node.get("price").isNull());
        require(node.get("orderAmount").isNull()
                || "MARKET".equals(node.get("orderType").stringValue()));
        if (original) {
            text(node.get("orderId"), 1, 256);
            text(node.get("status"), 1, 32);
            decimal(node.get("quantity"), false);
            decimal(node.get("filledQuantity"), true);
        }
    }

    private static void normalizations(JsonNode input, JsonNode args, JsonNode changes) {
        require(changes.isArray() && changes.size() <= 8);
        Map<String, JsonNode> actual = new TreeMap<>();
        for (JsonNode change : changes) {
            keys(change, Set.of("field", "before", "after"));
            String field = text(change.get("field"), 1, 32);
            require(NORMALIZED.contains(field) && actual.put(field, change) == null);
            for (String name : Set.of("before", "after")) {
                if (!change.get(name).isNull()) {
                    text(change.get(name), 1, 32);
                }
            }
            equal(change.get("before"), input.has(field) ? input.get(field) : JSON.nullNode());
            equal(change.get("after"), args.has(field) ? args.get(field) : JSON.nullNode());
            require(!change.get("before").equals(change.get("after")));
        }
        for (String field : NORMALIZED) {
            JsonNode before = input.has(field) ? input.get(field) : JSON.nullNode();
            JsonNode after = args.has(field) ? args.get(field) : JSON.nullNode();
            require(before.equals(after) != actual.containsKey(field));
        }
        for (String field : Set.of("orderId", "side", "orderType")) {
            if (input.has(field)) {
                equal(input.get(field), args.get(field));
            }
        }
    }

    private static String openColumn(
            ConnectorAction action, Long keyId, String column, String stored, TextCipher cipher) {
        if (keyId == null) {
            return stored;
        }
        try {
            return cipher.open(keyId, action.userId(), aad(action, column), stored)
                    .orElseThrow();
        } catch (RuntimeException ignored) {
            throw new IllegalStateException("financial snapshot decryption failed");
        }
    }

    private static String aad(ConnectorAction action, String column) {
        return "connector_action_execution:" + action.id() + ":column:" + column + ":user:" + action.userId();
    }

    private static String lengthPrefix(String value) {
        return bytes(value) + ":" + value;
    }

    private static JsonNode object(String raw, int maximum) {
        JsonNode node = parse(raw, maximum);
        require(node.isObject());
        return node;
    }

    private static JsonNode parse(String raw, int maximum) {
        require(raw != null && StandardCharsets.UTF_8.newEncoder().canEncode(raw) && bytes(raw) <= maximum);
        try {
            JsonNode node = JSON.readTree(raw);
            require(node != null);
            return node;
        } catch (JacksonException ignored) {
            throw invalid();
        }
    }

    private static void keys(JsonNode node, Set<String> names) {
        require(node != null && node.isObject() && node.propertyNames().equals(names));
    }

    private static String text(JsonNode node, int minimum, int maximum) {
        require(node != null && node.isString());
        String value = node.stringValue();
        require(value.length() >= minimum
                && value.length() <= maximum
                && StandardCharsets.UTF_8.newEncoder().canEncode(value)
                && value.chars().noneMatch(Character::isISOControl));
        return value;
    }

    private static void decimal(JsonNode node, boolean zero) {
        String value = text(node, 1, 30);
        require(value.matches("[0-9]+(\\.[0-9]+)?") && (zero || new BigDecimal(value).signum() > 0));
    }

    private static int bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static void equal(JsonNode left, JsonNode right) {
        require(left != null && right != null && left.equals(right));
    }

    private static void require(boolean condition) {
        if (!condition) {
            throw invalid();
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("invalid financial execution snapshot");
    }
}
