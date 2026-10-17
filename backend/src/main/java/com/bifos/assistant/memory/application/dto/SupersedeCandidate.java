package com.bifos.assistant.memory.application.dto;

/** 코드가 찾은 대체 후보 하나다. {@code newer} 가 {@code older} 를 대신한다는 제안이 된다. */
public record SupersedeCandidate(MemoryLinkItem newer, MemoryLinkItem older, double score) {
}
