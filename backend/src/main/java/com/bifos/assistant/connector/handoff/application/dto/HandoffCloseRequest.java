package com.bifos.assistant.connector.handoff.application.dto;

/** 넘기기 닫기 요청의 본문이다. {@code outcome} 이 없으면 거절한다. */
public record HandoffCloseRequest(HandoffCloseOutcome outcome) {
}
