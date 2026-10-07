package com.bifos.assistant.proactive.domain.type;

/** 살펴보기를 연 계기다. */
public enum CheckTrigger {
    /** 사용자가 단추를 눌러 열었다. */
    MANUAL,
    /** 매일 깨우기가 열었다. */
    SCHEDULED,
    /**
     * 행동 정책의 {@code EXECUTE} 가 열었다(ADR-20261007 autonomy-policy). 읽기 경계에서만 돌고, 답과 보고와 발견을 남기지 않고
     * 문제 후보만 저장한다.
     */
    AUTONOMY;

    /** 사람이 지켜보지 않는 실행인가. 사용자 대화 한 자리를 남겨 두는 백그라운드 자리를 쓴다. */
    public boolean unattended() {
        return this != MANUAL;
    }
}
