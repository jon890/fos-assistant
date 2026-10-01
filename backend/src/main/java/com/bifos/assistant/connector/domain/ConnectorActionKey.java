package com.bifos.assistant.connector.domain;

import com.bifos.assistant.shared.util.Sha256;

/**
 * 같은 도구 호출의 판정을 두 번 남기지 않게 하는 키다. {@code connector_action.dedupe_key} 에 적는다(ADR-048).
 *
 * <p>hook 의 요청이 늦게 닿거나 다시 와도 같은 키가 나와 줄이 하나만 남는다. 첫 줄이 위임 키의 {@code v1} 과 달라
 * 두 키는 서로 겹치지 않는다.
 *
 * @param value 소문자 16진수 64자
 */
public record ConnectorActionKey(String value) {
    private static final String VERSION_LINE = "v1-connector";

    /**
     * 다섯 줄을 이 순서로 {@code "\n"} 하나로 이어 SHA-256 을 계산한다.
     *
     * @throws IllegalArgumentException 어느 값이든 비었을 때. 빈 값으로 만든 키가 다른 호출과 겹치지 않게 한다
     */
    public static ConnectorActionKey of(String profileName, String rootSessionId, String sessionId, String toolCallId) {
        return new ConnectorActionKey(Sha256.hex(String.join(
                "\n",
                VERSION_LINE,
                require("profileName", profileName),
                require("rootSessionId", rootSessionId),
                require("sessionId", sessionId),
                require("toolCallId", toolCallId))));
    }

    private static String require(String name, String part) {
        if (part == null || part.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return part;
    }
}
