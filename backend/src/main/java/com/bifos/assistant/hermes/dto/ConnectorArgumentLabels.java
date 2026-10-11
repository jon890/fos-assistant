package com.bifos.assistant.hermes.dto;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import tools.jackson.databind.json.JsonMapper;

/** 도구별 인자 표시 이름만 검증한다. 불량 메타데이터는 정책과 카탈로그를 거절하지 않는다. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ConnectorArgumentLabels {
    private static final Pattern KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,30}");
    private static final Set<String> RESERVED = Set.of("__proto__", "constructor", "prototype");
    private static final Pattern FORBIDDEN = Pattern.compile(
        "[\\x00-\\x1f\\x7f-\\x9f\\u061c\\u200e\\u200f\\u2028\\u2029\\u202a-\\u202e\\u2066-\\u2069]");
    private static final String SPACE = " \u00a0\u1680\u2000\u2001\u2002\u2003\u2004\u2005\u2006\u2007\u2008\u2009\u200a\u202f\u205f\u3000\ufeff";
    private static final JsonMapper JSON = JsonMapper.builder().build();


    /** 원문 키와 값은 바꾸지 않는다. 반환 map은 소유 사본이다. */
    public static Map<String, String> read(Map<?, ?> labels) {
        if (labels == null || labels.size() > 64) {
            return Map.of();
        }
        Set<String> values = new HashSet<>();
        for (Map.Entry<?, ?> entry : labels.entrySet()) {
            if (!(entry.getKey() instanceof String key) || !KEY.matcher(key).matches() || RESERVED.contains(key)
                || !(entry.getValue() instanceof String value) || !validValue(value) || !values.add(value)
                || labels.containsKey(value) && !key.equals(value)) {
                return Map.of();
            }
        }
        if (JSON.writeValueAsString(labels).getBytes(StandardCharsets.UTF_8).length > 16384) {
            return Map.of();
        }
        @SuppressWarnings("unchecked")
        Map<String, String> checked = (Map<String, String>)labels;
        return Map.copyOf(checked);
    }


    private static boolean validValue(String value) {
        int length = value.codePointCount(0, value.length());
        if (length < 1 || length > 80 || SPACE.indexOf(value.charAt(0)) >= 0
            || SPACE.indexOf(value.charAt(value.length() - 1)) >= 0 || FORBIDDEN.matcher(value).find()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) {
                    return false;
                }
            } else if (Character.isLowSurrogate(current)) {
                return false;
            }
        }
        return true;
    }
}
