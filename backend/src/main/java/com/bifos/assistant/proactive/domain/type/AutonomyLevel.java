package com.bifos.assistant.proactive.domain.type;

/** 행동 정책이 후보 하나에 허락한 수준이다. 뜻은 {@code docs/backend/autonomy-policy.md} 의 「행동 수준」 이 갖는다. */
public enum AutonomyLevel {
    IGNORE,
    SURFACE,
    ASK_APPROVAL,
    EXECUTE
}
