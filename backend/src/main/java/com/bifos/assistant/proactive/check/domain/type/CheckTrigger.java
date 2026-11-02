package com.bifos.assistant.proactive.check.domain.type;

/** 살펴보기를 연 계기다. */
public enum CheckTrigger {
    /** 사용자가 단추를 눌러 열었다. */
    MANUAL,
    /** 매일 깨우기가 열었다. */
    SCHEDULED,
    /** 옛 행동 정책이 열었다. 새로 열지 않고, 저장된 옛 줄을 읽는 쪽이 건너뛰려고 값만 남겼다. */
    AUTONOMY;


    /** 사람이 지켜보지 않는 실행인가. 사용자 대화 한 자리를 남겨 두는 백그라운드 자리를 쓴다. */
    public boolean unattended() {
        return this != MANUAL;
    }
}
