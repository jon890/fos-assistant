package com.bifos.assistant.usage.execution.application.dto;

/**
 * plugin 의 {@code post_tool_call} 이 보낸 도구 하나의 기록을 가린 값이다.
 *
 * <p>오류 글 원문은 담지 않는다. 받는 쪽이 가린 요약만 넘긴다(스펙 run-trace REQ6).
 *
 * @param toolName hook 이 본 도구의 등록 이름. 사건 줄과 이름이 다르면 옮기지 않는다
 * @param durationMs hook 이 잰 걸린 시간. 모르면 null
 * @param errorType 실패한 도구의 오류 종류. 성공했거나 모르면 null
 * @param errorSummary 가린 뒤 자른 오류 요약. 남기지 않으면 null
 */
public record ToolTrace(String toolName, Long durationMs, String errorType, String errorSummary) {
}
