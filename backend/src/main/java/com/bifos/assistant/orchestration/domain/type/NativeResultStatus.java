package com.bifos.assistant.orchestration.domain.type;

/**
 * Hermes 내장 위임 하위 에이전트의 끝 소식이 알린 결과다(ADR-20261117 / native-delegation-wake).
 *
 * <p>끝 소식의 {@code child_status} 가 {@code completed} 면 {@link #SUCCEEDED}, 그 밖의 값은 모두 {@link #FAILED} 다.
 */
public enum NativeResultStatus {
    SUCCEEDED,
    FAILED;


    /** 끝 소식의 {@code child_status} 를 읽는다. 모르는 값과 빈 값은 실패다. */
    public static NativeResultStatus fromChildStatus(String childStatus) {
        return "completed".equals(childStatus) ? SUCCEEDED : FAILED;
    }
}
