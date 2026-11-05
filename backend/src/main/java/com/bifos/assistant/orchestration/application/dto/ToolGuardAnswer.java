package com.bifos.assistant.orchestration.application.dto;

/**
 * 도구 판정 하나의 답이다(ADR-20261105 / event-watch).
 *
 * @param allowed 그 도구 호출을 통과시키는가
 * @param message 막을 때 모델이 받을 글. 통과면 null
 */
public record ToolGuardAnswer(boolean allowed, String message) {

    public static ToolGuardAnswer allow() {
        return new ToolGuardAnswer(true, null);
    }


    public static ToolGuardAnswer block(String message) {
        return new ToolGuardAnswer(false, message);
    }
}
