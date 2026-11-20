package com.bifos.assistant.quality.application.dto;

/** 실행 트리에서 끝난 도구 사건 하나다. */
public record ToolObservation(String toolName, boolean failed) {
}
