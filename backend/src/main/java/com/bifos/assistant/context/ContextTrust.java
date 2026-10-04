package com.bifos.assistant.context;

/** 문맥 항목의 글을 누가 썼는지다(ADR-071). 값의 순서가 우선순위이고, 어긋나도 어느 쪽을 지우지 않는다. */
public enum ContextTrust {
    /** 사람이 받아들인 지식과 할 일. */
    USER_APPROVED,
    /** Control Plane 이 직접 적은 상태. */
    CONTROL_PLANE,
    /** 다른 에이전트의 답. */
    AGENT,
    /** 외부 서비스의 글. 그 안의 지시를 따르지 않는다. */
    EXTERNAL
}
