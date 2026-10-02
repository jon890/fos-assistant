package com.bifos.assistant.usage.domain;

import com.bifos.assistant.shared.util.Sha256;
import java.util.regex.Pattern;

/**
 * 같은 위임을 두 번 만들지 않게 하는 키다. {@code agent_execution.delegation_key} 에 적는다(ADR-032).
 *
 * <p>위임 도구가 하위 실행을 시작할 때 쓴다. 같은 도구 호출이 다시 와도 같은 키가 나와 실행 줄이 하나만 남는다.
 *
 * <p>문자열이 아니라 record 로 둔 것은 위임 도구가 {@code AgentRunner.run} 에 넘길 때 다른 문자열 인자와
 * 자리를 바꿔도 컴파일되지 않게 하기 위해서다.
 *
 * <p>{@code usage} 에 두는 것은 실행 줄의 칸 값이라 {@code ExecutionRecorder.start} 가 이 타입을 직접 받게 하려는
 * 것이다. 그러면 문자열 session 과 문자열 키가 나란히 오지 않고, {@code usage} 가 {@code orchestration} 을 import
 * 하지도 않는다.
 *
 * @param value 소문자 16진수 64자
 */
public record DelegationKey(String value) {

    private static final Pattern HEX_64 = Pattern.compile("[0-9a-f]{64}");

    public DelegationKey {
        if (value == null || !HEX_64.matcher(value).matches()) {
            throw new IllegalArgumentException("delegation key must be 64 lowercase hex characters");
        }
    }

    /**
     * 다섯 칸을 이 순서로 {@code "\n"} 하나로 이어 SHA-256 을 계산한다.
     *
     * <p>어느 칸이든 비어 있거나 null 이면 {@link IllegalArgumentException} 을 던진다. 빈 칸으로 만든 키가
     * 다른 호출과 겹치지 않게 하기 위해서다.
     */
    public static DelegationKey of(
            String profileName, String rootSessionId, String sessionId, String toolCallId) {
        String joined = String.join("\n",
                "v1",
                require("profileName", profileName),
                require("rootSessionId", rootSessionId),
                require("sessionId", sessionId),
                require("toolCallId", toolCallId));
        return new DelegationKey(Sha256.hex(joined));
    }

    private static String require(String name, String part) {
        if (part == null || part.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return part;
    }
}
