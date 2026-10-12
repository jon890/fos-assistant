package com.bifos.assistant.connector.application;

import static com.bifos.assistant.connector.application.ConnectorExecutionSnapshot.equal;
import static com.bifos.assistant.connector.application.ConnectorExecutionSnapshot.keys;
import static com.bifos.assistant.connector.application.ConnectorExecutionSnapshot.require;
import static com.bifos.assistant.connector.application.ConnectorExecutionSnapshot.text;

import com.bifos.assistant.shared.util.Sha256;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 일반 주문의 표시와 모델·실행 인자를 검증한다. 저장과 암호화는 snapshot이 맡는다. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class ConnectorOrderSnapshot {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> SUMMARY = Set.of(("account,symbol,market,currency,side,quantity,orderAmount,price,"
        + "orderType,timeInForce,operation,orderId,original,normalization")
            .split(","));
    static final Set<String> ORIGINAL = Set.of(("orderId,symbol,market,currency,side,quantity,orderAmount,price,"
        + "orderType,timeInForce,status,filledQuantity")
            .split(","));
    static final Set<String> EXPECTED = Set.of(
        ("orderId,symbol,currency,side,quantity,orderAmount,price," + "orderType,timeInForce,status,execution")
            .split(","));
    private static final Set<String> ORDER_VALUES = Set.of("symbol", "side", "quantity", "orderAmount", "price", "orderType", "timeInForce");


    /** 기존 일반 주문 검증을 이동하며 정규화 의미 보존도 함께 적용한다. */
    static void validate(JsonNode input, JsonNode args, JsonNode summary, JsonNode scope, String operation) {
        keys(summary, SUMMARY);
        display(summary, false);
        require(summary.get("account").isString() && summary.get("account").stringValue().matches("[0-9]{4}"));
        equal(summary.get("operation"), JSON.valueToTree(operation));
        validateArgs(input, args, summary, scope, operation);
        ConnectorNormalization.validate(input, args, summary.get("normalization"), JSON.nullNode());
    }


    /** 일반 주문 의도 키의 정렬과 길이 접두 표기다. v2 는 연결 번호 대신 커넥터 이름을 넣는다. */
    static String requestKey(JsonNode execution, Long userId, String connectorId, String tool) {
        Map<String, String> values = new TreeMap<>();
        for (var entry : execution.properties()) {
            if (Set.of("clientOrderId", "expected_order").contains(entry.getKey())) {
                continue;
            }
            require(entry.getValue().isString());
            String value = entry.getValue().stringValue();
            if (Set.of("quantity", "orderAmount", "price").contains(entry.getKey())) {
                value = ConnectorNormalization.canonicalDecimal(value);
            }
            values.put(entry.getKey(), value);
        }
        require(!values.containsKey("userId") && !values.containsKey("connectorId") && !values.containsKey("tool"));
        values.put("userId", userId.toString());
        values.put("connectorId", connectorId);
        values.put("tool", tool);
        StringBuilder body = new StringBuilder(lengthPrefix("fos-financial-intent-v2"));
        values.forEach((name, value) -> body.append(lengthPrefix(name)).append(lengthPrefix(value)));
        return Sha256.hex(body.toString());
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
        require("LIMIT".equals(node.get("orderType").stringValue()) != node.get("price").isNull());
        require(node.get("orderAmount").isNull()
            || "MARKET".equals(node.get("orderType").stringValue()));
        if (original) {
            text(node.get("orderId"), 1, 256);
            text(node.get("status"), 1, 32);
            decimal(node.get("quantity"), false);
            decimal(node.get("filledQuantity"), true);
        }
    }


    private static void decimal(JsonNode node, boolean zero) {
        String value = text(node, 1, 30);
        require(value.matches("[0-9]+(\\.[0-9]+)?") && (zero || new BigDecimal(value).signum() > 0));
    }


    private static String lengthPrefix(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length + ":" + value;
    }


    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("invalid financial execution snapshot");
    }
}
