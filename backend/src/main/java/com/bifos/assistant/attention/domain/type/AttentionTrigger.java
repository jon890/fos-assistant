package com.bifos.assistant.attention.domain.type;

/**
 * 항목이 판정 후보가 된 까닭이다. 뜻은 {@code docs/features/attention.md} 의 「후보와 trigger」 표가 갖는다.
 *
 * <p>사용자 제어와 지표 사건이 이 이름을 저장하므로, 아직 채우는 후보가 없는 값도 이름을 먼저 둔다.
 */
public enum AttentionTrigger {
    EXECUTION_FAILED,
    DELIVERY_FAILED,
    APPROVAL_PENDING,
    MEMORY_PROPOSED,
    MEMORY_SUPERSEDE_PROPOSED,
    FOLLOW_UP_PROPOSED,
    FOLLOW_UP_OPEN,
    DELEGATION_RUNNING,
    DELEGATION_FINISHED,
    CONVERSATION_RECENT,
    /** 옛 「먼저 다룰 문제」 다. 제안 카드가 대신해 후보를 내지 않고, 이미 저장된 사건을 읽으려고 이름만 남긴다. */
    PROBLEM_SURFACED,
    PROACTIVE_REPORT_TRIGGER
}
