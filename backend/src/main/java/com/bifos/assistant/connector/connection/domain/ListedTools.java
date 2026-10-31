package com.bifos.assistant.connector.connection.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 표준 플러그인({@code schema: 3}) 연결의 마지막 확인에서 MCP 서버가 {@code tools/list} 로 낸 도구들이다
 * (ADR-20261101 / plugin-without-assistant-json).
 *
 * <p>읽기 표시({@code readOnlyHint})가 참인 도구만 첫 승인 뒤 상시 허락을 줄 수 있다. 표시는 남의 서버가 정하므로 판정을 느슨하게
 * 하지 않는다. 첫 호출은 늘 사람이 승인한다.
 *
 * @param tools 원래 도구 이름을 키로, 읽기 표시가 참인가를 값으로 둔 것
 */
public record ListedTools(Map<String, Boolean> tools) {

    public ListedTools {
        tools = tools == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(tools));
    }


    /** 그 도구가 목록에 있고 읽기 표시가 참인가. 이름이 null 이면 거짓이다. */
    public boolean readOnly(String toolName) {
        return toolName != null && Boolean.TRUE.equals(tools.get(toolName));
    }


    /** 읽기 표시가 참인 도구 수다. */
    public int readOnlyCount() {
        return (int)tools.values().stream().filter(Boolean.TRUE::equals).count();
    }


    /** 그 도구의 읽기 표시를 거둔 목록이다. 목록에 없는 도구면 그대로다. */
    public ListedTools withoutReadOnly(String toolName) {
        if (!tools.containsKey(toolName)) {
            return this;
        }
        Map<String, Boolean> next = new LinkedHashMap<>(tools);
        next.put(toolName, false);
        return new ListedTools(next);
    }
}
