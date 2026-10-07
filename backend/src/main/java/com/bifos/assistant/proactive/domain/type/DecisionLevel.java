package com.bifos.assistant.proactive.domain.type;

/** 축별 크기다. 비용과 위험의 HIGH 는 부담이 크다는 뜻이며, UNKNOWN 은 낮다는 뜻이 아니다. */
public enum DecisionLevel {
    LOW,
    MEDIUM,
    HIGH,
    UNKNOWN
}
