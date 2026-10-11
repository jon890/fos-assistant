package com.bifos.assistant.connector.application;

import static com.bifos.assistant.connector.application.ConnectorExecutionSnapshot.equal;
import static com.bifos.assistant.connector.application.ConnectorExecutionSnapshot.keys;
import static com.bifos.assistant.connector.application.ConnectorExecutionSnapshot.require;
import static com.bifos.assistant.connector.application.ConnectorExecutionSnapshot.text;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import java.util.Locale;
import tools.jackson.databind.JsonNode;

/** 정규화 기록의 일치와 별개로 prepare가 원래 의도를 보존했는지 판정한다. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class ConnectorNormalization {
    private static final Set<String> NORMALIZED = Set.of("symbol", "quantity", "orderAmount", "price", "timeInForce");


    /** 기록의 전후 대조·완전성과 의도 보존을 각각 검사한다. */
    static void validate(JsonNode input, JsonNode args, JsonNode changes, JsonNode nullValue) {
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
            equal(change.get("before"), input.has(field) ? input.get(field) : nullValue);
            equal(change.get("after"), args.has(field) ? args.get(field) : nullValue);
            require(!change.get("before").equals(change.get("after")));
            require(ConnectorNormalization.preserves(field, change.get("before"), change.get("after")));
        }
        for (String field : NORMALIZED) {
            JsonNode before = input.has(field) ? input.get(field) : nullValue;
            JsonNode after = args.has(field) ? args.get(field) : nullValue;
            require(before.equals(after) != actual.containsKey(field));
        }
        for (String field : Set.of("orderId", "side", "orderType")) {
            if (input.has(field)) {
                equal(input.get(field), args.get(field));
            }
        }
    }

    /** 이미 타입과 표기를 검증한 변경 칸에 허용된 변환만 적용한다. */
    static boolean preserves(String field, JsonNode before, JsonNode after) {
        if (!after.isString()) {
            return false;
        }
        if ("timeInForce".equals(field)) {
            return before.isNull() && "DAY".equals(after.stringValue());
        }
        if (!before.isString()) {
            return false;
        }
        String value = before.stringValue();
        return switch (field) {
            case "symbol" -> value.toUpperCase(Locale.ROOT).equals(after.stringValue());
            case "quantity", "orderAmount", "price" -> canonicalDecimal(value).equals(after.stringValue());
            default -> false;
        };
    }

    /** 앞선 decimal 타입 검사 뒤에 사용하며 부동소수점 변환 없이 0 표기만 제거한다. */
    static String canonicalDecimal(String value) {
        return new BigDecimal(value).stripTrailingZeros().toPlainString();
    }
}
