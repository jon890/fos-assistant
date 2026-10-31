package com.bifos.assistant.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.conversation.application.MemoryUseService;
import com.bifos.assistant.chat.conversation.application.dto.MemoryUseReaction;
import com.bifos.assistant.chat.conversation.domain.ChatMessage;
import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.conversation.infra.ChatMessageRepository;
import com.bifos.assistant.chat.conversation.infra.ConversationRepository;
import com.bifos.assistant.feedback.application.DecisionFeedbackRecorder;
import com.bifos.assistant.feedback.application.dto.FeedbackEntry;
import com.bifos.assistant.feedback.domain.FeedbackEvent;
import com.bifos.assistant.feedback.domain.type.FeedbackActor;
import com.bifos.assistant.feedback.domain.type.FeedbackEventType;
import com.bifos.assistant.feedback.domain.type.FeedbackSubjectType;
import com.bifos.assistant.feedback.infra.FeedbackEventRepository;
import com.bifos.assistant.mcp.McpCallSigner;
import com.bifos.assistant.memory.application.MemoryService;
import com.bifos.assistant.memory.domain.entity.Memory;
import com.bifos.assistant.memory.domain.type.MemoryRetrieval;
import com.bifos.assistant.memory.domain.type.MemoryScope;
import com.bifos.assistant.memory.domain.type.MemorySensitivity;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.usage.execution.domain.AgentExecution;
import com.bifos.assistant.usage.execution.domain.ExecutionContextSource;
import com.bifos.assistant.usage.execution.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.execution.infra.ExecutionContextSourceRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 「참고한 기억」 의 「관련 없음」, 「틀림」 단추가 판단 피드백 사건만 남기고 기억 내용을 바꾸지 않는지 확인한다
 * (ADR-20261015 / memory-maintenance). 계약은 {@code docs/features/memory.md} 의 「참고한 기억의 단추」 가 갖는다.
 */
@BackendIntegrationTest
class MemoryUseReactionTest {
    private static final String PROFILE = "memory-use-reaction";

    @Autowired
    MemoryUseService uses;

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
    FeedbackEventRepository events;

    @Autowired
    DecisionFeedbackRecorder recorder;

    @Autowired
    AppUserRepository users;

    @Autowired
    JdbcTemplate jdbc;

    private CurrentUser dad;
    private CurrentUser kid;
    private Conversation conversation;
    private Memory used;
    private Memory unused;
    private AgentExecution run;


    @BeforeEach
    void setUp() {
        AppUser saved = users.save(AppUser.of("memory-use-reaction-" + UUID.randomUUID() + "@example.com", "아빠", 1L, UserRole.ADMIN));
        dad = new CurrentUser(saved.id(), saved.email(), "아빠", 1L, UserRole.ADMIN);
        kid = new CurrentUser(saved.id() + 1_000_000L, "kid-" + saved.id() + "@example.com", "아이", 1L, UserRole.MEMBER);
        conversation = conversations.save(Conversation.startedBy(dad.id(), "참고한 기억 단추", null, Instant.now()));
        used = memoryService.create(
            dad, MemoryScope.USER, "딸 이름", "딸 이름은 홍길순이다", "core", MemoryRetrieval.SEARCH, MemorySensitivity.NORMAL);
        unused = memoryService.create(
            dad, MemoryScope.USER, "취미", "등산을 좋아한다", "core", MemoryRetrieval.SEARCH, MemorySensitivity.NORMAL);
        run = McpCallSigner.running(executions, agents, dad.id(), conversation.id(), PROFILE, "fos-" + UUID.randomUUID());
        messages.save(ChatMessage.fromAssistant(conversation.id(), "답", run.id(), Instant.now()));
        contextSources.save(ExecutionContextSource.of(run.id(), 0, "MEMORY_FACTS", "memory:" + used.id(), "INLINE", "FRESH"));
    }


    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM decision_feedback_event WHERE user_id = ?", dad.id());
        jdbc.update("DELETE FROM execution_context_source WHERE execution_id = ?", run.id());
        jdbc.update("DELETE FROM chat_message WHERE conversation_id = ?", conversation.id());
        jdbc.update("DELETE FROM agent_execution WHERE id = ?", run.id());
        jdbc.update("DELETE FROM conversation WHERE user_id = ?", dad.id());
        jdbc.update("DELETE FROM memory_revision WHERE owner_user_id = ?", dad.id());
        jdbc.update("DELETE FROM memory WHERE owner_user_id = ?", dad.id());
        jdbc.update("DELETE FROM app_user WHERE id = ?", dad.id());
    }


    @Test
    @DisplayName("「틀림」 과 「관련 없음」 은 memory 열쇠에 사건을 하나씩 남기고 기억의 제목, 본문, 판, 상태, 자동 보관 허락은 그대로다")
    void reactionRecordsEventWithoutChangingMemory() {
        Memory before = memories.findById(used.id()).orElseThrow();

        uses.react(dad, conversation.id(), run.id(), used.id(), MemoryUseReaction.WRONG);
        uses.react(dad, conversation.id(), run.id(), used.id(), MemoryUseReaction.IRRELEVANT);

        assertThat(eventsOf(used))
            .extracting(
                FeedbackEvent::subjectType,
                FeedbackEvent::eventType,
                FeedbackEvent::actor,
                FeedbackEvent::conversationId,
                FeedbackEvent::originExecutionId,
                FeedbackEvent::subjectVersion)
            .containsExactly(
                tuple(FeedbackSubjectType.MEMORY, FeedbackEventType.MARKED_WRONG, FeedbackActor.USER, conversation.id(), run.id(), "1"),
                tuple(FeedbackSubjectType.MEMORY, FeedbackEventType.MARKED_IRRELEVANT, FeedbackActor.USER, conversation.id(), run.id(), "1"));
        Memory after = memories.findById(used.id()).orElseThrow();
        assertThat(after)
            .extracting(Memory::title, Memory::content, Memory::revision, Memory::status, Memory::archivable, Memory::retrieval)
            .containsExactly(before.title(), before.content(), before.revision(), before.status(), false, before.retrieval());
        assertThat(after.updatedAt()).isEqualTo(before.updatedAt());
        assertThat(uses.usesOf(dad, conversation.id()))
            .as("단추를 눌러도 참고한 기억 목록과 주입 대상은 그대로다")
            .hasSize(1);
    }


    @Test
    @DisplayName("그 답이 쓰지 않은 기억과 남의 기억은 없는 것과 같은 거절이고 사건을 남기지 않는다")
    void rejectsMemoryNotUsedByThatAnswer() {
        assertThatThrownBy(() -> uses.react(dad, conversation.id(), run.id(), unused.id(), MemoryUseReaction.WRONG))
            .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.MEMORY_NOT_FOUND));
        assertThatThrownBy(() -> uses.react(kid, conversation.id(), run.id(), used.id(), MemoryUseReaction.WRONG))
            .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.MEMORY_NOT_FOUND));
        assertThatThrownBy(() -> uses.react(dad, conversation.id(), run.id() + 1_000_000L, used.id(), MemoryUseReaction.WRONG))
            .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.MEMORY_NOT_FOUND));

        assertThat(eventsOf(used)).isEmpty();
        assertThat(eventsOf(unused)).isEmpty();
    }


    @Test
    @DisplayName("단추를 누른 대화를 지우면 그 단추 사건만 지우고 같은 기억의 다른 대화 사건은 남긴다")
    void forgettingConversationKeepsOtherEventsOfSameMemory() {
        Conversation other = conversations.save(Conversation.startedBy(dad.id(), "기억을 제안한 대화", null, Instant.now()));
        recorder.record(FeedbackEntry.of(
            dad.id(), FeedbackSubjectType.MEMORY, used.id(), FeedbackEventType.ACCEPTED, FeedbackActor.USER, Instant.now())
            .conversation(other.id()));
        uses.react(dad, conversation.id(), run.id(), used.id(), MemoryUseReaction.IRRELEVANT);

        recorder.forgetConversation(dad.id(), conversation.id());

        assertThat(eventsOf(used))
            .extracting(FeedbackEvent::eventType, FeedbackEvent::conversationId)
            .containsExactly(tuple(FeedbackEventType.ACCEPTED, other.id()));
    }


    private List<FeedbackEvent> eventsOf(Memory memory) {
        return events.findByUserIdAndSubjectKeyInOrderByOccurredAtAscIdAsc(dad.id(), List.of("memory:" + memory.id()));
    }
}
