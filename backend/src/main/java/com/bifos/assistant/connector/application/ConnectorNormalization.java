package com.bifos.assistant.connector.application;

import java.math.BigDecimal;
import java.util.Locale;
import tools.jackson.databind.JsonNode;

/** 정규화 기록의 일치와 별개로 prepare가 원래 의도를 보존했는지 판정한다. */
final class ConnectorNormalization {
    private ConnectorNormalization() {}

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
