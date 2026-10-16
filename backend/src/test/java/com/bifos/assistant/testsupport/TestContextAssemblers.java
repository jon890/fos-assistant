package com.bifos.assistant.testsupport;

import com.bifos.assistant.context.ContextAssembler;
import com.bifos.assistant.context.ContextProperties;
import com.bifos.assistant.context.MemorySearchSection;
import com.bifos.assistant.context.MemorySearchSectionProperties;
import com.bifos.assistant.memory.application.MemoryService;
import com.bifos.assistant.memory.application.relevance.MemoryRelevance;
import com.bifos.assistant.memory.application.relevance.MemoryRelevanceProperties;
import com.bifos.assistant.memory.application.relevance.QueryEmbedder;
import java.time.Clock;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 설정을 바꾼 조립기를 직접 만드는 테스트가 쓴다. 질문과 관련된 기억 구역은 기본 설정과 키워드 점수만 쓴다. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class TestContextAssemblers {

    public static ContextAssembler of(MemoryService memories, ContextProperties properties, Clock clock) {
        return of(memories, properties, clock, MemorySearchSectionProperties.defaults());
    }


    public static ContextAssembler of(
        MemoryService memories, ContextProperties properties, Clock clock, MemorySearchSectionProperties search) {
        MemorySearchSection section = new MemorySearchSection(
            MemoryRelevance.keywordOnly(MemoryRelevanceProperties.defaults()), search, QueryEmbedder.disabled());
        return new ContextAssembler(memories, properties, clock, section);
    }
}
