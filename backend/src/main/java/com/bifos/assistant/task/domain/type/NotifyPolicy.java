package com.bifos.assistant.task.domain.type;

/** 발화 결과를 알림으로 알릴지다. DB 에 이름 그대로 저장된다. */
public enum NotifyPolicy {
    /** 성공, 실패, 건너뜀을 모두 알린다. */
    ALWAYS,
    /** 실패와 건너뜀만 알린다. */
    ON_FAILURE,
    /** 알리지 않는다. */
    NEVER
}
