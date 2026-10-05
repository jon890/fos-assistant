package com.bifos.assistant.proactive.domain.type;

/**
 * 결과 블록을 읽지 못한 까닭이다. {@link CheckOutcome#INVALID_RESULT} 일 때만 채운다. 모델 글은 담지 않고 어느 단계에서 떨어졌는지만
 * 남긴다.
 */
public enum CheckInvalidReason {
    /** 답이 비었다. */
    EMPTY_ANSWER,
    /** 닫는 태그가 없거나 그 앞에 여는 태그가 없다. */
    NO_BLOCK,
    /** 태그 사이가 JSON 객체 하나가 아니다. */
    NOT_JSON,
    /** {@code version} 이 없거나 1 이 아니다. */
    BAD_VERSION,
    /** {@code outcome} 이 없거나 모르는 값이다. */
    BAD_OUTCOME
}
