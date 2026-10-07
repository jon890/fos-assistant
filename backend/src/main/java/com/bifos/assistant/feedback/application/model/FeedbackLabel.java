package com.bifos.assistant.feedback.application.model;

/**
 * 제안 하나에 대한 사용자의 첫 반응을 읽은 값이다. 저장하지 않고 replay 읽기 모델이 사건에서 매번 다시 읽는다. 규칙은 {@code
 * docs/backend/decision-feedback.md} 의 「반응 읽기」 가 갖는다.
 */
public enum FeedbackLabel {
    /** 받아들이거나 승인했다. */
    ACCEPTED,
    /** 거절하거나 숨기거나 받아들인 뒤 그만뒀다. 그 제안 하나에 대한 일회성 반응이다. */
    DECLINED,
    /** 미루기만 했다. 지금은 아니라는 뜻이고 싫다는 뜻이 아니다. */
    DEFERRED,
    /** 보였지만 반응이 없다. 학습 표본에서 뺀다. */
    NO_RESPONSE,
    /** 사용자에게 보인 기록이 없다. 자동 실행의 결과처럼 반응을 받을 자리가 없었다. */
    NOT_SURFACED
}
