package com.bifos.assistant.memory.application.model;

import com.bifos.assistant.memory.domain.entity.Memory;
import com.bifos.assistant.memory.domain.entity.MemoryLink;

/**
 * 사람이 받아들이거나 거절할 대체 제안 하나와 그 양 끝 항목이다(ADR-20261015 / memory-link).
 *
 * @param newMemory 대신하는 새 항목({@code from})
 * @param oldMemory 대신 받는 옛 항목({@code to})
 */
public record SupersedeProposal(MemoryLink link, Memory newMemory, Memory oldMemory) {
}
