package com.bifos.assistant.feedback.domain.type;

/**
 * 판단 피드백 기록의 사건 종류다. 실제 화면과 API 흐름에서 일어나는 것만 둔다. 어느 서비스가 어느 사건을 남기는지는
 * {@code DecisionFeedbackRecorder.record} 의 호출 지점이 갖는다.
 *
 * <p>응답하지 않음(무시)은 사건이 아니다. {@code SURFACED} 뒤에 사용자 반응이 없다는 사실로만 읽고 싫어함으로 적지 않는다.
 */
public enum FeedbackEventType {
    /** 사용자가 볼 수 있는 자리에 제안을 올렸다. 주체는 에이전트나 시스템이다. */
    SURFACED,
    /** 제안을 받아들였다. */
    ACCEPTED,
    /** 제안을 숨겼거나, 받아들인 할 일을 그만뒀다. */
    DISMISSED,
    /** 제안을 미뤘다. */
    POSTPONED,
    /** 제안에서 온 할 일을 고쳤다. */
    EDITED,
    /** 승인 줄을 승인했다. */
    APPROVED,
    /** 제안이나 승인 줄을 거절했다. */
    REJECTED,
    /** 실행이 성공했다. 제안에서 온 할 일을 끝낸 것도 여기다. 주체는 사용자나 시스템이다. */
    EXECUTION_SUCCEEDED,
    /** 실행이 실패했거나 시작하지 못했다. 주체는 시스템이다. */
    EXECUTION_FAILED
}
