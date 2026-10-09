package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.application.MemoryUse;
import com.bifos.assistant.chat.application.MemoryUseService;
import com.bifos.assistant.chat.domain.ChatMessage;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ChatMessageRepository;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.mcp.McpCallSigner;
import com.bifos.assistant.memory.application.MemoryService;
import com.bifos.assistant.memory.domain.Memory;
import com.bifos.assistant.memory.domain.type.MemoryRetrieval;
import com.bifos.assistant.memory.domain.type.MemoryScope;
import com.bifos.assistant.memory.domain.type.MemorySensitivity;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.usage.application.model.MemoryUseVia;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.ExecutionContextSource;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.infra.ExecutionContextSourceRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 대화의 답마다 그 실행이 본문을 받은 기억을 지금 볼 수 있는 것만 내는지 확인한다. 계약은 {@code docs/features/memory.md} 의
 * 「답마다 참고한 기억」 이 갖는다(ADR-20261008 / memory-facts).
 */
@BackendIntegrationTest
class MemoryUseServiceTest {
    private static final String PROFILE = "chat-memory-use";

    @Autowired
    MemoryUseService service;

    @Autowired
    MemoryService memoryService;

    @Autowired
    MemoryRepository memories;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    ChatMessageRepository messages;

    @Autowired
    AgentExecutionRepository executions;

    @Autowired
    AgentRepository agents;

    @Autowired
    ExecutionContextSourceRepository contextSources;

    @Autowired
    JdbcTemplate jdbc;

    private CurrentUser dad;
    private CurrentUser kid;
    private Conversation conversation;
    private final List<Long> createdMemoryIds = new ArrayList<>();
    private final List<Long> conversationIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        long suffix = Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000L) + 5_000_000L;
        dad = new CurrentUser(suffix, "dad-" + suffix + "@example.com", "아빠", 1L, UserRole.ADMIN);
        kid = new CurrentUser(suffix + 1, "kid-" + suffix + "@example.com", "아이", 1L, UserRole.MEMBER);
        conversation = newConversation(dad);
    }

    @AfterEach
    void tearDown() {
        for (Long conversationId : conversationIds) {
            jdbc.update(
                    "DELETE FROM execution_context_source WHERE execution_id IN"
                            + " (SELECT id FROM agent_execution WHERE conversation_id = ?)",
                    conversationId);
            jdbc.update("DELETE FROM chat_message WHERE conversation_id = ?", conversationId);
            jdbc.update("DELETE FROM agent_execution WHERE conversation_id = ?", conversationId);
            jdbc.update("DELETE FROM conversation WHERE id = ?", conversationId);
        }
        for (Long memoryId : createdMemoryIds) {
            jdbc.update("DELETE FROM memory_revision WHERE memory_id = ?", memoryId);
        }
        memories.deleteAllById(createdMemoryIds);
    }

    @Test
    @DisplayName("본문을 받은 개인 사실과 항상 층과 읽은 항목만 기록한 순서로 나오고 제목만 실은 줄과 빠진 줄은 나오지 않는다")
    void listsOnlyLoadedAndReadItemsInOrder() {
        Memory fact = memory(dad, MemoryScope.USER, "딸 이름", "딸 이름은 하나다", MemoryRetrieval.SEARCH);
        Memory always = memory(dad, MemoryScope.GROUP, "우리 집 규칙", "저녁은 같이 먹는다", MemoryRetrieval.ALWAYS);
        Memory indexed = memory(dad, MemoryScope.USER, "색인 항목", "색인 본문", MemoryRetrieval.SEARCH);
        Memory omitted = memory(dad, MemoryScope.USER, "빠진 항목", "빠진 본문", MemoryRetrieval.ALWAYS);
        Memory read = memory(dad, MemoryScope.USER, "경력 요약", "경력 본문", MemoryRetrieval.SEARCH);
        AgentExecution run = answeredRun(conversation, true);
        source(run, 0, "MEMORY_FACTS", fact, "INLINE");
        source(run, 1, "MEMORY_ALWAYS", always, "INLINE");
        source(run, 2, "MEMORY_INDEX", indexed, "TITLE_ONLY");
        source(run, 3, "MEMORY_ALWAYS", omitted, "OMITTED");
        source(run, 4, "MEMORY_READ", read, "INLINE");

        List<MemoryUse> uses = service.usesOf(dad, conversation.id());

        assertThat(uses)
                .extracting(
                        MemoryUse::executionId, MemoryUse::memoryId, MemoryUse::title, MemoryUse::scope, MemoryUse::via)
                .containsExactly(
                        tuple(run.id(), fact.id(), "딸 이름", MemoryScope.USER, MemoryUseVia.FACTS),
                        tuple(run.id(), always.id(), "우리 집 규칙", MemoryScope.GROUP, MemoryUseVia.ALWAYS),
                        tuple(run.id(), read.id(), "경력 요약", MemoryScope.USER, MemoryUseVia.READ));
    }

    @Test
    @DisplayName("같은 항목이 실렸고 읽히기도 했으면 앞의 실린 것 하나만 나온다")
    void keepsOnlyTheFirstWhenLoadedAndReadBoth() {
        Memory fact = memory(dad, MemoryScope.USER, "딸 이름", "딸 이름은 하나다", MemoryRetrieval.SEARCH);
        AgentExecution run = answeredRun(conversation, true);
        source(run, 0, "MEMORY_FACTS", fact, "INLINE");
        source(run, 1, "MEMORY_READ", fact, "INLINE");
        source(run, 2, "MEMORY_READ", fact, "INLINE");

        assertThat(service.usesOf(dad, conversation.id()))
                .extracting(MemoryUse::memoryId, MemoryUse::via)
                .containsExactly(tuple(fact.id(), MemoryUseVia.FACTS));
    }

    @Test
    @DisplayName("답마다 따로 모으고 실행 번호 오름차순으로 낸다")
    void ordersByExecutionIdAscending() {
        Memory first = memory(dad, MemoryScope.USER, "첫째", "첫째 본문", MemoryRetrieval.SEARCH);
        Memory second = memory(dad, MemoryScope.USER, "둘째", "둘째 본문", MemoryRetrieval.SEARCH);
        AgentExecution earlier = answeredRun(conversation, true);
        AgentExecution later = answeredRun(conversation, true);
        source(later, 0, "MEMORY_FACTS", second, "INLINE");
        source(earlier, 0, "MEMORY_FACTS", first, "INLINE");

        assertThat(service.usesOf(dad, conversation.id()))
                .extracting(MemoryUse::executionId, MemoryUse::memoryId)
                .containsExactly(tuple(earlier.id(), first.id()), tuple(later.id(), second.id()));
    }

    @Test
    @DisplayName("지운 항목과 다른 사용자의 개인 항목과 제안으로 돌아간 항목은 빠지고 제목은 지금 제목이다")
    void filtersByCurrentVisibilityAndShowsCurrentTitle() {
        Memory deleted = memory(dad, MemoryScope.USER, "지울 항목", "지울 본문", MemoryRetrieval.SEARCH);
        Memory foreign = memory(kid, MemoryScope.USER, "아이 항목", "아이 본문", MemoryRetrieval.SEARCH);
        Memory reverted = memory(dad, MemoryScope.USER, "되돌릴 항목", "되돌릴 본문", MemoryRetrieval.SEARCH);
        Memory renamed = memory(dad, MemoryScope.USER, "옛 제목", "고칠 본문", MemoryRetrieval.SEARCH);
        AgentExecution run = answeredRun(conversation, true);
        source(run, 0, "MEMORY_FACTS", deleted, "INLINE");
        source(run, 1, "MEMORY_FACTS", foreign, "INLINE");
        source(run, 2, "MEMORY_FACTS", reverted, "INLINE");
        source(run, 3, "MEMORY_FACTS", renamed, "INLINE");
        source(run, 4, "MEMORY_READ", foreign, "INLINE");
        memoryService.delete(dad, deleted.id());
        Memory proposal = memories.findById(reverted.id()).orElseThrow();
        proposal.restoreProposal(Instant.now());
        memories.save(proposal);
        jdbc.update("UPDATE memory SET title = ? WHERE id = ?", "새 제목", renamed.id());

        assertThat(service.usesOf(dad, conversation.id()))
                .extracting(MemoryUse::memoryId, MemoryUse::title)
                .containsExactly(tuple(renamed.id(), "새 제목"));
    }

    @Test
    @DisplayName("MEMORY_READ 이지만 body_mode 가 INLINE 이 아닌 줄과 memory: 가 아닌 ref 는 건너뛴다")
    void skipsReadRowsThatAreNotInlineOrNotMemoryRefs() {
        Memory read = memory(dad, MemoryScope.USER, "읽은 항목", "읽은 본문", MemoryRetrieval.SEARCH);
        Memory titleOnly = memory(dad, MemoryScope.USER, "제목만", "제목만 본문", MemoryRetrieval.SEARCH);
        Memory omitted = memory(dad, MemoryScope.USER, "빠진 읽기", "빠진 본문", MemoryRetrieval.SEARCH);
        AgentExecution run = answeredRun(conversation, true);
        source(run, 0, "MEMORY_READ", titleOnly, "TITLE_ONLY");
        source(run, 1, "MEMORY_READ", omitted, "OMITTED");
        sourceRef(run, 2, "MEMORY_READ", "connector:" + read.id(), "INLINE");
        sourceRef(run, 3, "MEMORY_READ", "memory:x", "INLINE");
        sourceRef(run, 4, "MEMORY_READ", "memory:", "INLINE");
        source(run, 5, "MEMORY_READ", read, "INLINE");

        assertThat(service.usesOf(dad, conversation.id()))
                .extracting(MemoryUse::memoryId, MemoryUse::via)
                .containsExactly(tuple(read.id(), MemoryUseVia.READ));
    }

    @Test
    @DisplayName("답 메시지가 없는 대화는 빈 목록이고 답이 없는 실행과 다른 대화의 답은 넣지 않는다")
    void returnsEmptyWithoutAnswersAndIgnoresOtherConversations() {
        Memory fact = memory(dad, MemoryScope.USER, "딸 이름", "딸 이름은 하나다", MemoryRetrieval.SEARCH);
        AgentExecution unanswered = answeredRun(conversation, false);
        source(unanswered, 0, "MEMORY_FACTS", fact, "INLINE");
        Conversation other = newConversation(dad);
        AgentExecution otherRun = answeredRun(other, true);
        source(otherRun, 0, "MEMORY_FACTS", fact, "INLINE");

        assertThat(service.usesOf(dad, conversation.id())).isEmpty();
        assertThat(service.usesOf(dad, newConversation(dad).id())).isEmpty();
    }

    @Test
    @DisplayName("읽은 항목은 그 에이전트가 받지 않는 collection 과 허용 없는 민감 항목과 항상 싣는 항목이면 빠진다")
    void dropsReadItemsTheAgentCannotReadNow() {
        Memory career = memory(dad, "career", MemorySensitivity.NORMAL, MemoryRetrieval.SEARCH, "커리어");
        Memory sensitive = memory(dad, "core", MemorySensitivity.SENSITIVE, MemoryRetrieval.SEARCH, "신원");
        Memory always = memory(dad, "core", MemorySensitivity.NORMAL, MemoryRetrieval.ALWAYS, "항상");
        Memory readable = memory(dad, "core", MemorySensitivity.NORMAL, MemoryRetrieval.SEARCH, "읽힘");
        AgentExecution run = answeredRun(conversation, true);
        List<Memory> reads = List.of(career, sensitive, always, readable);
        for (int position = 0; position < reads.size(); position++) {
            source(run, position, "MEMORY_READ", reads.get(position), "INLINE");
        }

        assertThat(service.usesOf(dad, conversation.id()))
                .extracting(MemoryUse::memoryId, MemoryUse::title)
                .containsExactly(tuple(readable.id(), "읽힘"));
    }

    @Test
    @DisplayName("에이전트가 없는 실행은 읽은 항목을 내지 않고 실은 항목은 그대로 낸다")
    void agentlessRunKeepsLoadedItemsAndDropsReads() {
        Memory fact = memory(dad, MemoryScope.USER, "딸 이름", "딸 이름은 하나다", MemoryRetrieval.SEARCH);
        Memory read = memory(dad, MemoryScope.USER, "읽은 항목", "읽은 본문", MemoryRetrieval.SEARCH);
        AgentExecution run = answeredRun(conversation, true, false);
        source(run, 0, "MEMORY_FACTS", fact, "INLINE");
        source(run, 1, "MEMORY_READ", read, "INLINE");

        assertThat(service.usesOf(dad, conversation.id()))
                .extracting(MemoryUse::memoryId, MemoryUse::via)
                .containsExactly(tuple(fact.id(), MemoryUseVia.FACTS));
    }

    private Conversation newConversation(CurrentUser owner) {
        Conversation saved = conversations.save(Conversation.startedBy(owner.id(), "참고한 기억 검사", null, Instant.now()));
        conversationIds.add(saved.id());
        return saved;
    }

    private Memory memory(
            CurrentUser owner, MemoryScope scope, String title, String content, MemoryRetrieval retrieval) {
        return memory(owner, scope, "core", title, content, retrieval, MemorySensitivity.NORMAL);
    }

    private Memory memory(
            CurrentUser owner,
            String collection,
            MemorySensitivity sensitivity,
            MemoryRetrieval retrieval,
            String title) {
        return memory(owner, MemoryScope.USER, collection, title, title + " 본문", retrieval, sensitivity);
    }

    private Memory memory(
            CurrentUser owner,
            MemoryScope scope,
            String collection,
            String title,
            String content,
            MemoryRetrieval retrieval,
            MemorySensitivity sensitivity) {
        Memory saved = memoryService.create(owner, scope, title, content, collection, retrieval, sensitivity);
        createdMemoryIds.add(saved.id());
        return saved;
    }

    private AgentExecution answeredRun(Conversation target, boolean answered) {
        return answeredRun(target, answered, true);
    }

    /** 그 대화에서 도는 실행을 만들고, 답 메시지를 남긴다. 에이전트가 없는 실행이면 {@code withAgent} 가 거짓이다. */
    private AgentExecution answeredRun(Conversation target, boolean answered, boolean withAgent) {
        String root = "fos-" + UUID.randomUUID();
        AgentExecution run = withAgent
                ? McpCallSigner.running(executions, agents, target.userId(), target.id(), PROFILE, root)
                : McpCallSigner.running(executions, target.userId(), target.id(), PROFILE, root);
        if (answered) {
            messages.save(ChatMessage.fromAssistant(target.id(), "답", run.id(), Instant.now()));
        }
        return run;
    }

    private void source(AgentExecution run, int position, String source, Memory memory, String bodyMode) {
        sourceRef(run, position, source, "memory:" + memory.id(), bodyMode);
    }

    private void sourceRef(AgentExecution run, int position, String source, String ref, String bodyMode) {
        contextSources.save(
                ExecutionContextSource.of(run.id(), position, source, ref, bodyMode, "FRESH", Instant.now()));
    }
}
