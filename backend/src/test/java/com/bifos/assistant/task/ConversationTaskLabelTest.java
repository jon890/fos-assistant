package com.bifos.assistant.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.application.ChatService;
import com.bifos.assistant.chat.application.ConversationAccess;
import com.bifos.assistant.chat.application.ConversationTaskLabels;
import com.bifos.assistant.chat.application.ModelTierService;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.chat.presentation.ChatController;
import com.bifos.assistant.chat.presentation.ChatDtos.ChooseModelRequest;
import com.bifos.assistant.chat.presentation.ChatDtos.ConversationView;
import com.bifos.assistant.chat.presentation.ChatDtos.RenameConversationRequest;
import com.bifos.assistant.chat.presentation.ChatEventStreams;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.task.domain.Task;
import com.bifos.assistant.task.domain.type.ConversationMode;
import com.bifos.assistant.task.domain.type.NotifyPolicy;
import com.bifos.assistant.task.infra.TaskRepository;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.user.application.UserDisplayNameService;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
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

/**
 * 작업이 만든 대화가 대화 응답에 작업의 공개 식별자와 이름을 싣는지 본다(ADR-078).
 *
 * <p>목록, 단건, 이름 바꾸기, 모델 고르기 응답은 같은 줄 모양을 쓴다. 웹이 이름 바꾸기 응답으로 목록의 줄을 통째로 바꾸므로 모두
 * 작업 칸을 실어야 한다.
 */
@BackendIntegrationTest
class ConversationTaskLabelTest {

    private static final Instant BASE = Instant.parse("2026-10-04T00:00:00Z");

    @Autowired
    ChatService chat;

    @Autowired
    ConversationAccess access;

    @Autowired
    AgentService agentService;

    @Autowired
    List<ConversationTaskLabels> taskLabels;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    TaskRepository tasks;

    @Autowired
    AgentRepository agents;

    @Autowired
    AppUserRepository users;

    private final List<Long> created = new ArrayList<>();
    private CurrentUser owner;
    private Agent agent;
    private ChatController controller;

    @BeforeEach
    void setUp() {
        String email = "task-label-" + UUID.randomUUID() + "@example.com";
        AppUser user = users.save(AppUser.of(email, email, 1L, UserRole.MEMBER, BASE));
        owner = new CurrentUser(user.id(), user.email(), user.displayName(), user.groupId(), user.role());
        String code = "task-label-" + UUID.randomUUID().toString().substring(0, 8);
        agent = agents.save(Agent.of(
                code,
                code,
                code,
                "http://agent-runtime.test/p/" + code,
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE,
                owner.id(),
                BASE));
        CurrentUserProvider provider = mock(CurrentUserProvider.class);
        when(provider.require()).thenReturn(owner);
        controller = new ChatController(
                chat,
                provider,
                new UserDisplayNameService(users),
                agentService,
                access,
                new ChatEventStreams(Duration.ofSeconds(20)),
                null,
                mock(ModelTierService.class),
                taskLabels);
    }

    @AfterEach
    void tearDown() {
        conversations.deleteAllById(created);
        tasks.deleteAll();
    }

    @Test
    @DisplayName("작업이 만든 대화는 목록, 단건, 이름 바꾸기, 모델 고르기 응답에 작업 번호와 이름을 싣고 보통 대화는 둘 다 비운다")
    void taskConversationCarriesTaskLabelInEveryResponse() {
        Task task = task("매달 커리어 정리");
        Conversation fromTask =
                conversation(Conversation.startedForTask(owner.id(), task.title(), agent.id(), task.id(), BASE));
        Conversation plain = conversation(Conversation.startedBy(owner.id(), "보통 대화", agent.id(), BASE.plusSeconds(1)));

        List<ConversationView> listed = controller.conversations(null, 30).items();

        assertThat(listed).hasSize(2);
        assertLabel(viewOf(listed, fromTask), task);
        assertNoLabel(viewOf(listed, plain));
        assertLabel(controller.conversation(fromTask.publicId()), task);
        assertNoLabel(controller.conversation(plain.publicId()));
        assertLabel(controller.rename(fromTask.publicId(), new RenameConversationRequest("새 제목")), task);
        assertNoLabel(controller.rename(plain.publicId(), new RenameConversationRequest("새 보통 제목")));
        assertLabel(controller.chooseModel(fromTask.publicId(), new ChooseModelRequest(null, null, null)), task);
    }

    @Test
    @DisplayName("지운 작업이 만든 대화도 작업 이름을 싣는다")
    void archivedTaskConversationStillCarriesTitle() {
        Task task = task("지운 작업");
        task.archive(BASE.plusSeconds(60));
        tasks.save(task);
        Conversation fromTask =
                conversation(Conversation.startedForTask(owner.id(), task.title(), agent.id(), task.id(), BASE));

        assertLabel(viewOf(controller.conversations(null, 30).items(), fromTask), task);
        assertLabel(controller.conversation(fromTask.publicId()), task);
    }

    private Task task(String title) {
        return tasks.save(Task.create(
                owner.id(), agent.id(), title, "정리해 줘", ConversationMode.NEW_PER_RUN, NotifyPolicy.ALWAYS, BASE));
    }

    private Conversation conversation(Conversation conversation) {
        Conversation saved = conversations.save(conversation);
        created.add(saved.id());
        return saved;
    }

    private static ConversationView viewOf(List<ConversationView> views, Conversation conversation) {
        return views.stream()
                .filter(view -> view.id().equals(conversation.publicId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("목록에 대화가 없다: " + conversation.publicId()));
    }

    private static void assertLabel(ConversationView view, Task task) {
        assertThat(view.taskId()).as("대화 %s 의 taskId", view.id()).isEqualTo(task.publicId());
        assertThat(view.taskTitle()).as("대화 %s 의 taskTitle", view.id()).isEqualTo(task.title());
    }

    private static void assertNoLabel(ConversationView view) {
        assertThat(view.taskId()).as("보통 대화 %s 의 taskId", view.id()).isNull();
        assertThat(view.taskTitle()).as("보통 대화 %s 의 taskTitle", view.id()).isNull();
    }
}
