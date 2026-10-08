package com.bifos.assistant.memory.application.model;

/** 관리자 화면의 항목 수를 누구의 항목으로 셌는지다. */
public enum AgentMemoryCountScope {
    /** 에이전트 주인의 USER 항목과 주인 그룹의 GROUP 항목을 셌다. */
    OWNER,
    /** 주인이 없어 관리자 그룹의 GROUP 항목만 셌다. */
    GROUP
}
