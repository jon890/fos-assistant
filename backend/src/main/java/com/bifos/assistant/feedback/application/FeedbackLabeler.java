package com.bifos.assistant.feedback.application;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 반응 사건의 {@code reason_code} 로 남기는 고정 이름을 갖는다.
 *
 * <p>사건을 첫 반응으로 읽는 함수는 호출하는 운영 코드가 없어 지웠다. 잘못된 학습을 막는 규칙(무응답은 싫어함이 아니고, 거절과 숨기기는
 * 그 제안 하나에 대한 일회성 반응이다)은 ADR-012 가 갖는다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class FeedbackLabeler {

    /** 지금 화면의 숨기기다. 상태가 바뀔 때까지만 가리므로 거절이 아니라 미루기처럼 읽는다. */
    public static final String ATTENTION_HIDE = "ATTENTION_HIDE";

    /** 지금 화면의 미루기다. */
    public static final String ATTENTION_SNOOZE = "ATTENTION_SNOOZE";

    /** 지금 화면의 숨기기와 미루기를 되돌렸다. */
    public static final String ATTENTION_RESTORE = "ATTENTION_RESTORE";

    /** 사용자가 {@code memory_remember} 의 기록을 되돌려 받아들인 제안을 다시 제안으로 돌렸다. 받아들임을 무른 것이다. */
    public static final String MEMORY_UNDO = "MEMORY_UNDO";
}
