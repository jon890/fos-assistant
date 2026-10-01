package com.bifos.assistant.connector.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 연결에 저장하는 칸 값이다.
 *
 * <p>비밀 칸의 원문은 담지 않는다. 비밀 칸은 앞 8자만 {@code secretPrefixes} 에 두고, 비밀이 아닌 칸은 값 그대로
 * {@code values} 에 둔다. 어느 칸이 비밀인지는 manifest 로 판정하므로 이 record 를 만드는 쪽이 나눠 넣는다.
 *
 * @param values 비밀이 아닌 칸의 값
 * @param secretPrefixes 비밀 칸의 앞 8자
 */
public record ConnectionFields(Map<String, String> values, Map<String, String> secretPrefixes) {

    public ConnectionFields {
        values = copy(values);
        secretPrefixes = copy(secretPrefixes);
    }

    public static ConnectionFields empty() {
        return new ConnectionFields(Map.of(), Map.of());
    }

    private static Map<String, String> copy(Map<String, String> source) {
        return source == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
