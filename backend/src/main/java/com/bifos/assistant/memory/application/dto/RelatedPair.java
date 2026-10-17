package com.bifos.assistant.memory.application.dto;

/** 자동 {@code RELATED} 로 맺을 쌍 하나다. */
public record RelatedPair(MemoryLinkItem target, MemoryLinkItem peer, double score) {
}
