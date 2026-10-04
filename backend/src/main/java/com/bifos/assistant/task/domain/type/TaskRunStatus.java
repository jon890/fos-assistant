package com.bifos.assistant.task.domain.type;

/** 발화 한 번의 상태다(ADR-077). DB 에 이름 그대로 저장된다. */
public enum TaskRunStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    SKIPPED
}
