package com.bifos.assistant.memory.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** 대화 뒤 Memory 제안을 만들지 정하는 설정이다. */
@Validated
@ConfigurationProperties(prefix = "assistant.memory.propose")
public record MemoryProposalProperties(boolean enabled) {
}
