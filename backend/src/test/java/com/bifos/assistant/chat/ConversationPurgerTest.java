package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.application.ConversationPurger;
import com.bifos.assistant.chat.application.ConversationWriter;
import com.bifos.assistant.chat.domain.ChatAttachment;
import com.bifos.assistant.chat.domain.ChatMessage;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.domain.ExecutionQuestion;
import com.bifos.assistant.chat.infra.ArtifactProperties;
import com.bifos.assistant.chat.infra.ArtifactStore;
import com.bifos.assistant.chat.infra.AttachmentStore;
import com.bifos.assistant.chat.infra.ChatAttachmentRepository;
import com.bifos.assistant.chat.infra.ChatMessageRepository;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.chat.infra.ExecutionQuestionRepository;
import com.bifos.assistant.hermes.StubHermesRunsClient;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.ExecutionEvent;
import com.bifos.assistant.usage.domain.type.ExecutionEventType;
import com.bifos.assistant.usage.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.infra.ExecutionEventRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
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

/** 지운 대화의 본문을 실제로 지우는지 확인한다. 일정을 기다리지 않고 직접 부른다. */
@BackendIntegrationTest
class ConversationPurgerTest {

    private static final Instant NOW = Instant.parse("2026-10-08T05:00:00Z");

    @Autowired
    ConversationPurger purger;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    ConversationWriter conversationWriter;

    @Autowired
    ChatMessageRepository messages;

    @Autowired
    ChatAttachmentRepository attachments;

    @Autowired
    AttachmentStore attachmentStore;

    @Autowired
    ArtifactStore artifactStore;

    @Autowired
    ArtifactProperties artifactProperties;

    @Autowired
    ExecutionQuestionRepository questions;

    @Autowired
    AgentExecutionRepository executions;

    @Autowired
    ExecutionEventRepository events;

    @Autowired
    AgentRepository agents;

    @Autowired
    AppUserRepository users;

    @Autowired
    StubHermesRunsClient hermes;

    @Autowired
    JdbcTemplate jdbc;

    private AppUser owner;
    private Agent agent;
    private final List<Filled> created = new ArrayList<>();

    @BeforeEach
    void setUp() {
        String email = "purge-" + UUID.randomUUID() + "@example.test";
        owner = users.save(AppUser.of(email, email, 1L, UserRole.MEMBER, NOW));
        String code = "purge-" + UUID.randomUUID().toString().substring(0, 8);
        agent = agents.save(Agent.of(
                code,
                "정리 대화 에이전트",
                code,
                "http://agent-runtime.test/p/" + code,
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE,
                owner.id(),
                NOW));
    }

    /** 지우지 않은 대화의 파일이 남으면 다음 실행에서 같은 번호의 파일과 부딪친다. */
    @AfterEach
    void tearDown() {
        for (Filled filled : created) {
            attachmentStore.delete(filled.attachment());
            artifactStore.deleteFolder(filled.conversationId());
        }
        created.clear();
    }

    @Test
    @DisplayName("지운 대화의 메시지와 첨부, 결과물, 실행 본문, Hermes session 을 지우고 사용량은 남긴다")
    void purgesBodiesOfDeletedConversationAndKeepsUsage() throws Exception {
        Filled filled = filledConversation(ExecutionStatus.SUCCEEDED);
        conversationWriter.deleteIfActive(filled.conversationId(), owner.id(), NOW.minusSeconds(60));

        purger.purgeDue(NOW);

        assertThat(messages.findByConversationIdOrderByIdAsc(filled.conversationId()))
                .isEmpty();
        assertThat(attachments.findByConversationIdOrderByIdAsc(filled.conversationId()))
                .isEmpty();
        assertThat(attachmentFile(filled.attachment())).doesNotExist();
        assertThat(artifactFolder(filled.conversationId())).doesNotExist();
        assertThat(questions.findById(filled.executionId())).isEmpty();
        AgentExecution execution = executions.findById(filled.executionId()).orElseThrow();
        assertThat(execution.outputText()).isNull();
        assertThat(execution.totalTokens()).isEqualTo(30L);
        assertThat(events.findByExecutionIdInOrderByExecutionIdAscSequenceAsc(List.of(filled.executionId())))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.detail()).isNull();
                    assertThat(event.toolName()).isEqualTo("web_search");
                });
        Conversation purged = conversations.findById(filled.conversationId()).orElseThrow();
        assertThat(purged.purgedAt()).isEqualTo(NOW);
        assertThat(purged.title()).isEmpty();
        assertThat(purged.hermesSessionId()).isNull();
        assertThat(deletedSessionIds()).contains(filled.sessionId());
    }

    @Test
    @DisplayName("지우지 않은 대화는 건드리지 않는다")
    void leavesActiveConversationsAlone() throws Exception {
        Filled filled = filledConversation(ExecutionStatus.SUCCEEDED);

        purger.purgeDue(NOW);

        assertThat(messages.findByConversationIdOrderByIdAsc(filled.conversationId()))
                .hasSize(2);
        assertThat(attachmentFile(filled.attachment())).exists();
        assertThat(conversations.findById(filled.conversationId()).orElseThrow().purgedAt())
                .isNull();

        assertThat(deletedSessionIds()).doesNotContain(filled.sessionId());
    }

    @Test
    @DisplayName("실행이 돌고 있으면 미루고, 끝난 뒤의 차례에 지운다")
    void waitsForRunningExecutionThenPurges() throws Exception {
        Filled filled = filledConversation(ExecutionStatus.RUNNING);
        conversationWriter.deleteIfActive(filled.conversationId(), owner.id(), NOW.minusSeconds(60));

        purger.purgeDue(NOW);

        assertThat(messages.findByConversationIdOrderByIdAsc(filled.conversationId()))
                .hasSize(2);
        assertThat(deletedSessionIds()).doesNotContain(filled.sessionId());

        jdbc.update("UPDATE agent_execution SET status = 'SUCCEEDED' WHERE id = ?", filled.executionId());
        purger.purgeDue(NOW.plusSeconds(60));

        assertThat(messages.findByConversationIdOrderByIdAsc(filled.conversationId()))
                .isEmpty();
        assertThat(deletedSessionIds()).contains(filled.sessionId());
    }

    @Test
    @DisplayName("끝난 실행의 자식이 아직 사용량 작업 줄을 받지 못했으면 미루고, 그 기간이 지나면 지운다")
    void waitsForUnscheduledChildUsage() throws Exception {
        Filled filled = filledConversation(ExecutionStatus.SUCCEEDED);
        events.save(ExecutionEvent.builder()
                .executionId(filled.executionId())
                .sequence(2)
                .eventType(ExecutionEventType.SUBAGENT_STARTED)
                .hermesSessionId("child-" + UUID.randomUUID())
                .occurredAt(NOW)
                .build());
        conversationWriter.deleteIfActive(filled.conversationId(), owner.id(), NOW.minusSeconds(60));

        purger.purgeDue(NOW);

        assertThat(messages.findByConversationIdOrderByIdAsc(filled.conversationId()))
                .hasSize(2);

        purger.purgeDue(NOW.plus(Duration.ofHours(25)));

        assertThat(messages.findByConversationIdOrderByIdAsc(filled.conversationId()))
                .isEmpty();
    }

    @Test
    @DisplayName("Hermes session 을 지우지 못하면 본문을 남기고, 기다린 뒤 다시 시도해 지운다")
    void keepsBodiesWhenHermesFailsAndRetriesLater() throws Exception {
        Filled filled = filledConversation(ExecutionStatus.SUCCEEDED);
        conversationWriter.deleteIfActive(filled.conversationId(), owner.id(), NOW.minusSeconds(60));
        Instant failedAt = NOW.plus(Duration.ofDays(1));
        hermes.failSessionDeletes(new ApiException(ErrorCode.HERMES_UNAVAILABLE, "down"));

        purger.purgeDue(failedAt);

        assertThat(messages.findByConversationIdOrderByIdAsc(filled.conversationId()))
                .hasSize(2);
        assertThat(conversations.findById(filled.conversationId()).orElseThrow().purgedAt())
                .isNull();

        hermes.failSessionDeletes(null);
        ChatAttachment blocked = attachments.findById(filled.attachment().id()).orElseThrow();
        assertThat(blocked.deletionRequestedAt()).isEqualTo(failedAt);
        assertThat(blocked.deletedAt()).isNull();
        assertThat(attachmentFile(blocked)).exists();
        purger.purgeDue(failedAt.plusSeconds(30));
        assertThat(messages.findByConversationIdOrderByIdAsc(filled.conversationId()))
                .as("기다리는 간격 안에서는 다시 시도하지 않는다")
                .hasSize(2);

        purger.purgeDue(failedAt.plus(Duration.ofMinutes(2)));

        assertThat(messages.findByConversationIdOrderByIdAsc(filled.conversationId()))
                .isEmpty();
        assertThat(deletedSessionIds()).contains(filled.sessionId());
    }

    @Test
    @DisplayName("본문을 지운 대화에는 늦게 온 메시지도 남기지 않는다")
    void refusesMessagesForPurgedConversation() throws Exception {
        Filled filled = filledConversation(ExecutionStatus.SUCCEEDED);
        conversationWriter.deleteIfActive(filled.conversationId(), owner.id(), NOW.minusSeconds(60));
        purger.purgeDue(NOW);

        assertThatThrownBy(() -> messages.save(
                        ChatMessage.fromAssistant(filled.conversationId(), "늦은 답", filled.executionId(), NOW)))
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.CONVERSATION_NOT_FOUND));
    }

    /** 메시지 둘과 사진 하나, 결과물 하나, 실행 하나와 그 사건과 질문 줄을 가진 대화를 만든다. */
    private Filled filledConversation(ExecutionStatus status) throws Exception {
        Conversation conversation =
                conversations.save(Conversation.startedBy(owner.id(), "건강검진 결과", agent.id(), NOW.minusSeconds(600)));
        String sessionId = "purge-session-" + UUID.randomUUID();
        conversationWriter.assignSessionIfAbsent(conversation.id(), sessionId);
        ChatMessage question =
                messages.save(ChatMessage.fromUser(conversation.id(), owner.id(), "콜레스테롤 수치가 240이야", NOW));
        AgentExecution execution = executions.save(AgentExecution.builder()
                .userId(owner.id())
                .conversationId(conversation.id())
                .agentId(agent.id())
                .profileName(agent.hermesProfile())
                .hermesSessionId(sessionId)
                .costMode(CostMode.SUBSCRIPTION)
                .model("example-model")
                .status(status)
                .tokens(10L, 0L, 20L, 30L)
                .timing(NOW.minusSeconds(30), NOW.minusSeconds(20))
                .build());
        execution.recordOutput("위임한 답 본문");
        executions.save(execution);
        messages.save(ChatMessage.fromAssistant(conversation.id(), "식단을 조절해 보세요", execution.id(), NOW));
        questions.save(ExecutionQuestion.of(execution.id(), question.id(), NOW));
        events.save(ExecutionEvent.builder()
                .executionId(execution.id())
                .sequence(1)
                .eventType(ExecutionEventType.TOOL_STARTED)
                .toolName("web_search")
                .detail("콜레스테롤 240 식단")
                .occurredAt(NOW)
                .build());
        ChatAttachment attachment = attachments.save(ChatAttachment.of(
                conversation.id(), owner.id(), "검진표.png", "image/png", 3, NOW.plus(Duration.ofDays(30)), NOW));
        attachment.nameStoredFile(AttachmentStore.storedName(attachment.id(), "png"));
        attachments.save(attachment);
        attachmentStore.save(attachment, new ByteArrayInputStream(new byte[] {1, 2, 3}));
        artifactStore.write(conversation.id(), "report/index.html", "<p>결과</p>".getBytes(StandardCharsets.UTF_8));
        Filled filled = new Filled(conversation.id(), execution.id(), sessionId, attachment);
        created.add(filled);
        return filled;
    }

    private List<String> deletedSessionIds() {
        return hermes.deletedSessions().stream()
                .map(StubHermesRunsClient.SessionLookup::sessionId)
                .toList();
    }

    private Path attachmentFile(ChatAttachment attachment) {
        return Path.of("build/test-attachments")
                .toAbsolutePath()
                .resolve("users")
                .resolve(AttachmentStore.userDirectoryKey(attachment.uploadedByUserId()))
                .resolve(String.valueOf(attachment.conversationId()))
                .resolve(attachment.id() + ".png");
    }

    private Path artifactFolder(Long conversationId) {
        return Path.of(artifactProperties.root()).toAbsolutePath().resolve(String.valueOf(conversationId));
    }

    private record Filled(Long conversationId, Long executionId, String sessionId, ChatAttachment attachment) {}
}
