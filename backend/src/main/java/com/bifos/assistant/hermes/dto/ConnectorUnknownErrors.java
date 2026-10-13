package com.bifos.assistant.hermes.dto;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * 카탈로그의 {@code unknown_errors} 다. 커넥터가 {@code outcome_unknown} 으로 선언한 코드와 그 재시도 식별자 칸 이름이다
 * (ADR-20261013 unknown-retry-key).
 *
 * <p>Control Plane 은 실행 경로의 504 본문을 형식만이 아니라 이 선언과도 대조한다. 대시보드 plugin 이 선언하지 않은 코드나 칸을
 * 실어도 에이전트 입력에 가지 않는다.
 *
 * @param retryKeys 코드마다 선언한 재시도 식별자 칸 이름. 선언하지 않은 코드는 빈 글이다
 */
public record ConnectorUnknownErrors(Map<String, String> retryKeys) {

    /** 선언이 없다. 옛 대시보드 plugin 의 카탈로그이거나 칸의 모양이 틀렸다. 어느 코드도 받지 않는다. */
    public static final ConnectorUnknownErrors NONE = new ConnectorUnknownErrors(Map.of());

    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

    private static final Pattern RETRY_KEY = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,31}");

    public ConnectorUnknownErrors {
        retryKeys = Map.copyOf(retryKeys);
    }


    /** 칸이 없거나 모양이 틀리면 {@link #NONE} 이다. 칸 하나가 틀려도 커넥터를 카탈로그에서 빼지 않는다. */
    public static ConnectorUnknownErrors read(JsonNode declared) {
        if (declared == null || !declared.isObject()) {
            return NONE;
        }
        Map<String, String> keys = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : declared.properties()) {
            JsonNode key = entry.getValue();
            boolean readable = CODE.matcher(entry.getKey()).matches()
                && (key.isNull() || key.isString() && RETRY_KEY.matcher(key.asString()).matches());
            if (!readable) {
                return NONE;
            }
            keys.put(entry.getKey(), key.isNull() ? "" : key.asString());
        }
        return new ConnectorUnknownErrors(keys);
    }


    /**
     * 받은 코드와 식별자를 선언과 대조한다. 선언하지 않은 코드면 빈 값이고, 식별자 칸 이름이 선언과 다르면 식별자만 버린다.
     */
    public Optional<ConnectorUnknownDetail> admit(ConnectorUnknownDetail received) {
        String declaredKey = retryKeys.get(received.code());
        if (declaredKey == null) {
            return Optional.empty();
        }
        if (received.retryKey() == null || declaredKey.equals(received.retryKey())) {
            return Optional.of(received);
        }
        return Optional.of(new ConnectorUnknownDetail(received.code(), null, null));
    }
}
