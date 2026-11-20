package com.bifos.assistant.testsupport;

import com.bifos.assistant.memory.application.dto.MemoryContextBudget;
import com.bifos.assistant.context.application.ContextAssembler;
import com.bifos.assistant.context.config.ContextProperties;
import com.bifos.assistant.context.application.MemorySearchSection;
import com.bifos.assistant.context.config.MemorySearchSectionProperties;
import com.bifos.assistant.memory.application.MemoryService;
import com.bifos.assistant.memory.application.MemoryRelevance;
import com.bifos.assistant.memory.config.MemoryRelevanceProperties;
import com.bifos.assistant.memory.application.QueryEmbedder;
import java.time.Clock;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 설정을 바꾼 조립기를 직접 만드는 테스트가 쓴다. 질문과 관련된 기억 구역은 기본 설정과 키워드 점수만 쓴다. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class TestContextAssemblers {

    /** 그룹 설정 줄이 없는 그룹의 기본 예산으로 조립한다. */
    public static ContextAssembler of(MemoryService memories, ContextProperties properties, Clock clock) {
        return of(memories, properties, MemoryContextBudget.DEFAULTS, clock, MemorySearchSectionProperties.defaults());
    }


    public static ContextAssembler of(
        MemoryService memories, ContextProperties properties, Clock clock, MemorySearchSectionProperties search) {
        return of(memories, properties, MemoryContextBudget.DEFAULTS, clock, search);
    }


    /** 요약 구역 예산과 기억 전체 상한을 정한 조립기다. 0 인 요약 예산은 요약 구역을 끈다. */
    public static ContextAssembler of(
        MemoryService memories, ContextProperties properties, MemoryContextBudget budget, Clock clock) {
        return of(memories, properties, budget, clock, MemorySearchSectionProperties.defaults());
    }


    public static ContextAssembler of(
        MemoryService memories,
        ContextProperties properties,
        MemoryContextBudget budget,
        Clock clock,
        MemorySearchSectionProperties search) {
        MemorySearchSection section = new MemorySearchSection(
            MemoryRelevance.keywordOnly(MemoryRelevanceProperties.defaults()), search, QueryEmbedder.disabled());
        return new ContextAssembler(memories, properties, groupId -> budget, clock, section);
    }
}
