package com.bifos.assistant.hermes.application.dto;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 표준 플러그인({@code schema: 3})의 MCP 서버가 {@code tools/list} 로 낸 도구들이다(ADR-20261101 / plugin-without-assistant-json).
 *
 * <p>도구 이름과 그 도구의 {@code readOnlyHint} 가 참인가만 담는다. 설명과 입력 schema 는 싣지 않는다. 성공 결과와 공통 어휘 가운데
 * 하나만 있다.
 *
 * @param tools 원래 도구 이름을 키로, 읽기 표시가 참인가를 값으로 둔 것. 서버가 낸 순서를 지킨다. 실패하면 빈 값
 * @param error 실패한 까닭. 성공하면 null
 */
public record ConnectorToolListing(Map<String, Boolean> tools, ConnectorCallError error) {

    public ConnectorToolListing {
        tools = tools == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(tools));
    }


    public static ConnectorToolListing success(Map<String, Boolean> tools) {
        return new ConnectorToolListing(tools, null);
    }


    public static ConnectorToolListing failure(ConnectorCallError error) {
        return new ConnectorToolListing(Map.of(), error);
    }


    public boolean ok() {
        return error == null;
    }
}
