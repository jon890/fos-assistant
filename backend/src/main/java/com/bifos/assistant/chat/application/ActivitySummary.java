package com.bifos.assistant.chat.application;

/** 답을 만들며 호출한 도구와 하위 에이전트 수와 걸린 시간이다. */
public record ActivitySummary(int toolCount, int subagentCount, Long durationMs) {
}
