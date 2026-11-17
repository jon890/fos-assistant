package com.bifos.assistant.chat.turn.application.dto;

/**
 * Hermes 내장 위임 결과 알림 줄의 상태다(ADR-20261117 / native-delegation-wake). 화면이 이 칸이 있는 알림 줄을 대화 에이전트의 이름표와
 * 저장된 글로 그린다.
 *
 * @param status {@code SUCCEEDED} 나 {@code FAILED}
 */
public record NativeResultView(String status) {

    /** 상태가 비면 null 이다. */
    public static NativeResultView of(String status) {
        return status == null ? null : new NativeResultView(status);
    }
}
