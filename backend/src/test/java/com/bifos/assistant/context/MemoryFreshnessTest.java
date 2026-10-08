package com.bifos.assistant.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.chat.application.ContextSourceRefs;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.memory.application.MemoryService;
import com.bifos.assistant.memory.domain.Memory;
import com.bifos.assistant.memory.domain.type.MemoryRetrieval;
import com.bifos.assistant.memory.domain.type.MemoryScope;
import com.bifos.assistant.memory.domain.type.MemorySensitivity;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.TestClock;
import com.bifos.assistant.usage.application.ExecutionContextSnapshot;
import com.bifos.assistant.usage.application.ExecutionRecorder;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.ExecutionContextSource;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.infra.ExecutionContextSourceRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Memory 수정 시각에서 문맥과 실행 출처의 신선도를 함께 판정하는지 확인한다. */
@BackendIntegrationTest
class MemoryFreshnessTest {

    private static final CurrentUser USER = new CurrentUser(82_941L, "user@example.com", "user", 5L, UserRole.ADMIN);
    private static final Instant NOW = Instant.parse("2026-10-08T00:00:00Z");

    @Autowired
    ContextAssembler assembler;

    @Autowired
    MemoryService memories;

    @Autowired
    MemoryRepository memoryRepository;

    @Autowired
    TestClock clock;

    @Autowired
    ExecutionRecorder recorder;

    @Autowired
    ExecutionContextSourceRepository contextSources;

    @Autowired
    AgentExecutionRepository executions;

    @Autowired
    ConversationRepository conversations;

    @BeforeEach
    void setUp() {
        contextSources.deleteAll();
        executions.deleteAll();
        memoryRepository.deleteAll();
        clock.set(NOW);
    }

    @Test
    @DisplayName("오래된 항상 항목과 색인 항목은 STALE이고 경계와 새 항목은 FRESH다")
    void classifiesAlwaysAndIndexedItemsByTheirUpdatedAt() {
        clock.set(NOW.minus(Duration.ofDays(181)));
        Memory staleAlways = memories.create(USER, MemoryScope.USER, "오래된 항상", "항상 본문", true);
        Memory staleIndex = memories.create(USER, MemoryScope.USER, "오래된 색인", "색인 본문", false);
        Memory staleOmitted = memories.create(USER, MemoryScope.USER, "오래된 생략", "가".repeat(9_000), true);
        clock.set(NOW.minus(Duration.ofDays(180)));
        Memory boundary = memories.create(USER, MemoryScope.USER, "경계", "경계 본문", true);
        clock.set(NOW.minus(Duration.ofDays(1)));
        Memory fresh = memories.create(USER, MemoryScope.USER, "새 항목", "새 본문", false);
        clock.set(NOW);

        assertThat(assembler.assembleForOwner(USER).bundle().items())
                .extracting(ContextItem::ref, ContextItem::freshness)
                .containsExactlyInAnyOrder(
                        tuple("memory:" + staleAlways.id(), ContextFreshness.STALE),
                        tuple("memory:" + staleIndex.id(), ContextFreshness.STALE),
                        tuple("memory:" + staleOmitted.id(), ContextFreshness.STALE),
                        tuple("memory:" + boundary.id(), ContextFreshness.FRESH),
                        tuple("memory:" + fresh.id(), ContextFreshness.FRESH));
        assertThat(assembler.assembleForOwner(USER).bundle().items())
                .filteredOn(item -> item.ref().equals("memory:" + staleOmitted.id()))
                .singleElement()
                .extracting(ContextItem::bodyMode)
                .isEqualTo(ContextBodyMode.OMITTED);
    }

    @Test
    @DisplayName("기본 기준이 0이어도 collection 기준은 따로 STALE과 FRESH를 판정한다")
    void usesCollectionStaleAfterWhenDefaultFreshnessIsDisabled() {
        clock.set(NOW.minus(Duration.ofDays(31)));
        Memory career = memories.create(
                USER, MemoryScope.USER, "커리어", "커리어 본문", "career", MemoryRetrieval.ALWAYS, MemorySensitivity.NORMAL);
        Memory defaultDisabled = memories.create(
                USER, MemoryScope.USER, "판정 끔", "본문", "core", MemoryRetrieval.SEARCH, MemorySensitivity.NORMAL);
        clock.set(NOW.minus(Duration.ofDays(29)));
        Memory careerFresh = memories.create(
                USER,
                MemoryScope.USER,
                "새 커리어",
                "새 커리어 본문",
                "career",
                MemoryRetrieval.SEARCH,
                MemorySensitivity.NORMAL);
        clock.set(NOW);
        ContextAssembler configured = new ContextAssembler(
                memories,
                new ContextProperties(
                        8_000,
                        4,
                        Duration.ofHours(6),
                        Duration.ZERO,
                        Map.of("career", Duration.ofDays(30)),
                        null,
                        null),
                clock);

        assertThat(configured.assembleForOwner(USER).bundle().items())
                .extracting(ContextItem::ref, ContextItem::freshness)
                .containsExactlyInAnyOrder(
                        tuple("memory:" + career.id(), ContextFreshness.STALE),
                        tuple("memory:" + defaultDisabled.id(), ContextFreshness.UNKNOWN),
                        tuple("memory:" + careerFresh.id(), ContextFreshness.FRESH));
    }

    @Test
    @DisplayName("수정 시각이 없는 대역 Memory는 UNKNOWN으로 판정한다")
    void returnsUnknownWhenMemoryHasNoUpdatedAt() {
        MemoryService mockedMemories = mock(MemoryService.class);
        Memory memory = mock(Memory.class);
        when(memory.id()).thenReturn(701L);
        when(memory.scope()).thenReturn(MemoryScope.USER);
        when(memory.sensitivity()).thenReturn(MemorySensitivity.NORMAL);
        when(memory.collection()).thenReturn(Memory.DEFAULT_COLLECTION);
        when(memory.content()).thenReturn("본문");
        when(memory.sealed()).thenReturn(false);
        when(mockedMemories.alwaysInjectedFor(any(), any())).thenReturn(List.of(memory));
        when(mockedMemories.indexedFor(any(), any())).thenReturn(List.of());
        ContextAssembler mockedAssembler = new ContextAssembler(
                mockedMemories,
                new ContextProperties(8_000, 4, Duration.ofHours(6), Duration.ofDays(180), Map.of(), null, null),
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(mockedAssembler.assembleForOwner(USER).bundle().items())
                .singleElement()
                .extracting(ContextItem::freshness)
                .isEqualTo(ContextFreshness.UNKNOWN);
    }

    @Test
    @DisplayName("실제 Memory에서 만든 STALE과 UNKNOWN 출처를 실행 기록에 함께 남긴다")
    void recordsStaleAndUnknownMemorySources() {
        clock.set(NOW.minus(Duration.ofDays(181)));
        Memory stale = memories.create(USER, MemoryScope.USER, "오래된 기억", "본문", true);
        Memory unknown = memories.create(
                USER, MemoryScope.USER, "판정 없는 기억", "본문", "disabled", MemoryRetrieval.SEARCH, MemorySensitivity.NORMAL);
        clock.set(NOW);
        ContextAssembler configured = new ContextAssembler(
                memories,
                new ContextProperties(
                        8_000,
                        4,
                        Duration.ofHours(6),
                        Duration.ofDays(180),
                        Map.of("disabled", Duration.ZERO),
                        null,
                        null),
                clock);
        AssembledContext context = configured.assembleForOwner(USER);
        Conversation conversation = conversations.save(Conversation.startedBy(USER.id(), "신선도", null, NOW));
        AgentExecution execution = recorder.start(
                USER,
                conversation.executionConversation(),
                agent(),
                null,
                null,
                new ExecutionContextSnapshot(
                        context.chars(),
                        null,
                        context.instructionsHash(),
                        context.omittedItems(),
                        ContextSourceRefs.of(context)));

        assertThat(contextSources.findByIdExecutionIdOrderByIdPositionAsc(execution.id()))
                .extracting(ExecutionContextSource::sourceRef, ExecutionContextSource::freshness)
                .containsExactlyInAnyOrder(
                        tuple("memory:" + stale.id(), "STALE"), tuple("memory:" + unknown.id(), "UNKNOWN"));
    }

    private static Agent agent() {
        return Agent.of(
                "memory-freshness-agent",
                "신선도 검사",
                "memory-freshness-agent",
                "http://runtime.test/p/memory-freshness",
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE,
                USER.id(),
                NOW);
    }
}
