package com.bifos.assistant.task.domain.type;

/** 서버가 내려가 있던 동안 지난 예정 시각을 어떻게 다루는지다(ADR-077). DB 에 이름 그대로 저장된다. */
public enum MissedPolicy {
    /** 지난 시각 가운데 가장 늦은 하나만 돌린다. */
    RUN_ONCE,
    /** 돌리지 않고 건너뛴 줄 하나만 남긴다. */
    SKIP
}
