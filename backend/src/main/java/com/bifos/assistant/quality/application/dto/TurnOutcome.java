package com.bifos.assistant.quality.application.dto;

/** 평가 대화에 문항 하나를 보낸 결과다(평가 실행기의 보내기). */
public enum TurnOutcome {
    /** {@code send} 가 돌아왔다. 실행이 실패했어도 실행 줄에 남아 판정이 읽는다. */
    DONE,
    /** 마감이 지났다. 도는 turn 은 멈췄다. */
    TIMED_OUT,
    /** 실행 줄이 생기기 전에 사용자나 대화가 바빠 거절됐다. */
    BUSY,
    /** 실행 줄이 생기기 전에 다른 까닭으로 거절됐다. */
    REJECTED
}
