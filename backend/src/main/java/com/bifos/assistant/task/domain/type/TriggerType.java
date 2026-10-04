package com.bifos.assistant.task.domain.type;

/** 예약 작업의 시각 종류다(ADR-077). DB 에 이름 그대로 저장된다. */
public enum TriggerType {
    /** 표준 5필드 cron 으로 반복한다. */
    CRON,
    /** 정한 시각에 한 번 돈다. */
    ONCE
}
