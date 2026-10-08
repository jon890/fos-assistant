package com.bifos.assistant.chat.application;

import com.bifos.assistant.memory.domain.type.MemoryScope;
import com.bifos.assistant.usage.application.model.MemoryUseVia;

/**
 * 대화의 답 하나가 본문을 받은 기억 하나다(ADR-20261008 / memory-facts). 제목과 범위는 지금 값이고 본문은 싣지 않는다.
 */
public record MemoryUse(Long executionId, Long memoryId, String title, MemoryScope scope, MemoryUseVia via) {}
