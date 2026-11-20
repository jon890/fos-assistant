package com.bifos.assistant.connector.connection.domain.type;

/** 바인딩의 껐다 켜기 단계다(ADR-20261120 / connector-auto-apply). 단계가 없으면 칸이 비어 있다. */
public enum CyclePhase {
    /** 껐다 켜기가 필요하다. 주인의 실행이 비거나 기다림 상한이 지나면 끈다. */
    IDLE_WAIT,
    /** 그 서버를 껐다. 반영 예정 시간이 지나면 다시 켠다. */
    OFF
}
