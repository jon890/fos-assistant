package com.bifos.assistant.chat;

import com.bifos.assistant.chat.artifact.application.GeneratedImageService;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.attachment.application.ChatContentMutationCoordinator;
import com.bifos.assistant.chat.attachment.application.dto.ChatContentMutationTarget;
import com.bifos.assistant.chat.conversation.application.ConversationAccess;
import com.bifos.assistant.chat.conversation.application.SourceReadSummaries;
import com.bifos.assistant.chat.conversation.domain.ChatMessage;
import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.conversation.infra.ChatMessageRepository;
import com.bifos.assistant.chat.conversation.infra.ConversationRepository;
import com.bifos.assistant.chat.delivery.application.AutoTurnNoticeStatuses;
import com.bifos.assistant.chat.turn.application.ChatService;
import com.bifos.assistant.chat.turn.presentation.ChatController;
import com.bifos.assistant.chat.turn.presentation.ChatEventStreams;
import com.bifos.assistant.chat.modelchoice.application.ModelTierService;
import com.bifos.assistant.orchestration.application.NativeDelegationResultSource;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.NativeResultFixtures;
import com.bifos.assistant.usage.execution.domain.AgentExecution;
import com.bifos.assistant.usage.execution.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.execution.infra.AgentExecutionRepository;
import com.bifos.assistant.user.application.UserDisplayNameService;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 이력 API 가 내장 위임 결과 알림 줄에 상태를 붙이고, 대화를 정리하면 결과 글이 비는지 본다(스펙 REQ14, 설계 DECISION40,
 * ADR-20261117 / native-delegation-wake).
 */
@BackendIntegrationTest
class NativeResultHistoryTest {
    private static final String PROFILE = "native-history";
    private static final String EMAIL = "native-history@example.com";

    @Autowired
    ChatService chat;

    @Autowired
    ConversationAccess access;

    @Autowired
    AgentService agentService;

    @Autowired
    UserDisplayNameService userNames;

    @Autowired
    AutoTurnNoticeStatuses noticeStatuses;

    @Autowired
    NativeDelegationResultSource source;

    @Autowired
    ChatContentMutationCoordinator mutations;

    @Autowired
    ApplicationContext context;

    @Autowired
    AppUserRepository users;

    @Autowired
    AgentRepository agents;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    ChatMessageRepository messages;

    @Autowired
    AgentExecutionRepository executions;

    @Autowired
    JdbcTemplate jdbc;

    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
    private CurrentUser dad;
    private Conversation conversation;
    private AgentExecution origin;


    @BeforeEach
    void setUp() {
        NativeResultFixtures.clear(jdbc, PROFILE);
        users.findByEmail(EMAIL).ifPresent(user -> {
            jdbc.update("DELETE FROM chat_message WHERE conversation_id IN (SELECT id FROM conversation WHERE user_id = ?)", user.id());
            jdbc.update("DELETE FROM agent_execution WHERE user_id = ?", user.id());
            jdbc.update("DELETE FROM conversation WHERE user_id = ?", user.id());
            jdbc.update("DELETE FROM agent WHERE owner_user_id = ?", user.id());
            users.delete(user);
        });
        AppUser user = users.save(AppUser.of(EMAIL, "아빠", 1L, UserRole.MEMBER));
        dad = new CurrentUser(user.id(), user.email(), user.displayName(), user.groupId(), user.role());
        when(currentUser.require()).thenReturn(dad);
        Agent chief = agents.save(Agent.of(
            PROFILE, "비서", PROFILE, "http://agent-runtime.test/p/" + PROFILE, CostMode.SUBSCRIPTION,
            CredentialScope.SHARED_HOUSEHOLD, AgentVisibility.PRIVATE, user.id()));
        conversation = conversations.save(Conversation.startedBy(user.id(), "대화", chief.id(), Instant.now()));
        origin = executions.save(AgentExecution.builder()
            .userId(user.id())
            .conversationId(conversation.id())
            .agentId(chief.id())
            .profileName(PROFILE)
            .costMode(CostMode.SUBSCRIPTION)
            .status(ExecutionStatus.SUCCEEDED)
            .startedAt(Instant.now().minusSeconds(600))
            .build());
    }


    @Test
    @DisplayName("처음 전한 알림 줄에만 결과 상태가 붙고, 다른 알림 줄은 비며, 다시 전달 뒤에도 같다")
    void firstNoticeCarriesStatusAndSurvivesRetry() {
        ChatMessage notice = messages.save(ChatMessage.fromSystem(conversation.id(), "하위 작업을 하지 못했어요 · 메일", Instant.now()));
        ChatMessage other = messages.save(ChatMessage.fromSystem(conversation.id(), "다른 알림", Instant.now()));
        long binding = NativeResultFixtures.binding(
            jdbc, PROFILE, dad.id(), origin.id(), true, Instant.now().minusSeconds(120), Instant.now().minusSeconds(60), "FAILED", "글", "메일");
        source.noticesSaved(Map.of(String.valueOf(binding), notice.id()));

        assertThat(statuses()).containsExactly(new Object[] {notice.id(), "FAILED"}, new Object[] {other.id(), null});

        ChatMessage retry = messages.save(ChatMessage.fromSystem(conversation.id(), "결과를 다시 전해요", Instant.now()));
        source.noticesSaved(Map.of(String.valueOf(binding), retry.id()));

        assertThat(statuses()).containsExactly(
            new Object[] {notice.id(), "FAILED"}, new Object[] {other.id(), null}, new Object[] {retry.id(), null});
    }


    @Test
    @DisplayName("남의 대화의 이력은 지금처럼 거절한다")
    void otherConversationIsRejected() {
        when(currentUser.require()).thenReturn(new CurrentUser(dad.id() + 1000, "other@example.com", "남", 1L, UserRole.MEMBER));

        assertThatThrownBy(() -> controller().messages(conversation.publicId())).isInstanceOf(ApiException.class);
    }


    @Test
    @DisplayName("대화를 정리하면 등록 줄의 맡긴 일과 결과 글과 key 가 비고 줄과 상태는 남는다")
    void purgeClearsTextsAndKeepsRow() {
        long binding = NativeResultFixtures.binding(
            jdbc, PROFILE, dad.id(), origin.id(), true, Instant.now().minusSeconds(120), Instant.now().minusSeconds(60), "SUCCEEDED", "결과", "일");
        jdbc.update("UPDATE hermes_session_binding SET result_key_id = 1 WHERE id = ?", binding);
        jdbc.update("UPDATE conversation SET deleted_at = ? WHERE id = ?", Timestamp.from(Instant.now().minus(Duration.ofDays(30))), conversation.id());
        Object purgeWriter = context.getBean("conversationPurgeWriter");

        Boolean purged = mutations.run(
            dad.id(),
            new ChatContentMutationTarget(conversation.id(), List.of()),
            () -> ReflectionTestUtils.invokeMethod(purgeWriter, "purge", conversation.id(), Instant.now()));

        assertThat(purged).isTrue();
        Map<String, Object> row = jdbc.queryForMap(
            "SELECT goal_preview, result_text, result_key_id, result_status, stopped_at FROM hermes_session_binding WHERE id = ?", binding);
        assertThat(row.get("goal_preview")).isNull();
        assertThat(row.get("result_text")).isNull();
        assertThat(row.get("result_key_id")).isNull();
        assertThat(row.get("result_status")).isEqualTo("SUCCEEDED");
        assertThat(row.get("stopped_at")).isNotNull();
    }


    private List<Object[]> statuses() {
        return controller().messages(conversation.publicId()).stream()
            .map(view -> new Object[] {view.id(), view.nativeResult() == null ? null : view.nativeResult().status()})
            .toList();
    }


    private ChatController controller() {
        return new ChatController(
            chat,
            currentUser,
            userNames,
            agentService,
            access,
            new ChatEventStreams(Duration.ofSeconds(1)),
            mock(ModelTierService.class),
            List.of(),
            mock(SourceReadSummaries.class),
            noticeStatuses,
            mock(GeneratedImageService.class));
    }
}
