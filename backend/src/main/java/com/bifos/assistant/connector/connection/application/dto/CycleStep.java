package com.bifos.assistant.connector.connection.application.dto;

/** 한 설치 시도가 껐다 켜기의 어느 걸음인가(ADR-20261120 / connector-auto-apply). */
public enum CycleStep {
    /** 껐다 켜기 걸음이 아닌 보통 설치다. */
    NONE,
    /** 그 서버를 끄는 설치({@code bind.cycle: "off"})다. */
    OFF,
    /** 끈 서버를 다시 켜는 보통 설치다. 답에 재시작이나 끄기가 없으면 반영 예정을 기다린다. */
    ON
}
