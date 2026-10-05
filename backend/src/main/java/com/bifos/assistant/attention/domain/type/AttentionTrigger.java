package com.bifos.assistant.attention.domain.type;

/**
 * 항목이 판정 후보가 된 까닭이다. 뜻은 {@code docs/backend/attention.md} 의 「후보와 trigger」 표가 갖는다.
 *
 * <p>사용자 제어와 지표 사건이 이 이름을 저장하므로, 아직 채우는 후보가 없는 값도 이름을 먼저 둔다.
 */
public enum AttentionTrigger {
    EXECUTION_FAILED,
    DELIVERY_FAILED,
    APPROVAL_PENDING,
    MEMORY_PROPOSED,
    FOLLOW_UP_PROPOSED,
    FOLLOW_UP_OPEN,
    DELEGATION_RUNNING,
    DELEGATION_FINISHED,
    CONVERSATION_RECENT,
    PROACTIVE_REPORT_TRIGGER
}
