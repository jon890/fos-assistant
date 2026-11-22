package com.bifos.assistant.quality.application.dto;

import java.time.Instant;

/** 남겨 둔 평가 대화의 메시지 하나다. 관리자가 실패 문항과 사람 비교 문항의 답을 읽는다. */
public record EvalMessageView(String role, String content, Long executionId, Instant createdAt) {
}
