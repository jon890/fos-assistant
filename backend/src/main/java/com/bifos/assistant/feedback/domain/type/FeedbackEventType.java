package com.bifos.assistant.feedback.domain.type;

/**
 * 판단 피드백 기록의 사건 종류다. 실제 화면과 API 흐름에서 일어나는 것만 둔다. 뜻과 기록 지점은 {@code
 * docs/backend/decision-feedback.md} 의 「사건」 이 갖는다.
 *
 * <p>응답하지 않음(무시)은 사건이 아니다. {@code SURFACED} 뒤에 사용자 반응이 없다는 사실로만 읽고 싫어함으로 적지 않는다.
 */
public enum FeedbackEventType {
    SURFACED,
    ACCEPTED,
    DISMISSED,
    POSTPONED,
    EDITED,
    APPROVED,
    REJECTED,
    EXECUTION_SUCCEEDED,
    EXECUTION_FAILED
}
