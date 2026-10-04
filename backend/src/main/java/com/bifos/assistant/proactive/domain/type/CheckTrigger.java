package com.bifos.assistant.proactive.domain.type;

/** 살펴보기를 연 계기다. */
public enum CheckTrigger {
    /** 사용자가 단추를 눌러 열었다. */
    MANUAL,
    /** 매일 깨우기가 열었다. 매일 깨우기는 아직 없다. */
    SCHEDULED
}
