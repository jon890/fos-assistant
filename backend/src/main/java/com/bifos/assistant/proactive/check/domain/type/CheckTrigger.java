package com.bifos.assistant.proactive.check.domain.type;

/** 살펴보기를 연 계기다. */
public enum CheckTrigger {
    /** 사용자가 단추를 눌러 열었다. */
    MANUAL,
    /** 매일 깨우기가 열었다. */
    SCHEDULED,
    /** 사건 감지가 새 항목을 보고 열었다(ADR-20261105 / event-watch). */
    EVENT;


    /** 사람이 지켜보지 않는 실행인가. 사용자 대화 한 자리를 남겨 두는 백그라운드 자리를 쓴다. */
    public boolean unattended() {
        return this != MANUAL;
    }


    /** 알릴 것이 없으면 대화와 보고를 남기지 않는 실행인가. 사람이 부르지 않은 실행이 모두 그렇다. */
    public boolean silentWhenEmpty() {
        return unattended();
    }
}
