package com.bifos.assistant.proactive.domain.type;

/**
 * 행동 수준을 정한 까닭이다. 조건은 {@code docs/backend/autonomy-policy.md} 의 「까닭 코드와 수준」 표와 같다.
 *
 * <p>선언 순서가 저장하는 순서다. 묶음은 수준을 정하는 순서로 쓰인다.
 */
public enum AutonomyReason {
    CANDIDATE_NOT_CURRENT(Group.IGNORE),
    EVALUATION_NOT_USABLE(Group.IGNORE),
    LOW_VALUE(Group.IGNORE),
    INSUFFICIENT_EVIDENCE(Group.WEAK_BASIS),
    LOW_CONFIDENCE(Group.WEAK_BASIS),
    UNKNOWN_JUDGEMENT(Group.WEAK_BASIS),
    STALE_EVALUATION(Group.WEAK_BASIS),
    STALE_EVIDENCE(Group.WEAK_BASIS),
    REPLAY_INPUT(Group.WEAK_BASIS),
    QUESTION_FOR_USER(Group.WEAK_BASIS),
    ACTION_UNDECLARED(Group.WEAK_BASIS),
    EXTERNAL_WRITE_REQUIRES_APPROVAL(Group.APPROVAL),
    INTERNAL_WRITE_REQUIRES_APPROVAL(Group.APPROVAL),
    SIDE_EFFECT_UNDECLARED(Group.APPROVAL),
    RISK_NOT_LOW(Group.APPROVAL),
    VALUE_NOT_HIGH(Group.EXECUTION_BLOCKED),
    COST_NOT_LOW(Group.EXECUTION_BLOCKED),
    USER_AUTONOMY_DISABLED(Group.EXECUTION_BLOCKED),
    WRITE_BOUNDARY_OPEN(Group.EXECUTION_BLOCKED),
    SOURCE_IS_AUTONOMOUS(Group.EXECUTION_BLOCKED),
    AGENT_NOT_STARTABLE(Group.EXECUTION_BLOCKED),
    EXECUTION_TAKEN(Group.EXECUTION_BLOCKED),
    ALREADY_EXECUTED(Group.EXECUTION_BLOCKED),
    READ_ONLY_SAFE(Group.SAFE);

    /** 까닭의 묶음이다. 앞 묶음이 있으면 뒤 묶음을 보지 않는다. */
    public enum Group {
        IGNORE,
        WEAK_BASIS,
        APPROVAL,
        EXECUTION_BLOCKED,
        SAFE
    }

    private final Group group;

    AutonomyReason(Group group) {
        this.group = group;
    }

    public Group group() {
        return group;
    }
}
