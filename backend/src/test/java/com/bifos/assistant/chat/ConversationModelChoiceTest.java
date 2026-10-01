package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentVisibility;
import com.bifos.assistant.agent.domain.CostMode;
import com.bifos.assistant.agent.domain.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.application.AttachmentProperties;
import com.bifos.assistant.chat.application.AttachmentService;
import com.bifos.assistant.chat.application.ChatService;
import com.bifos.assistant.chat.application.ConversationAccess;
import com.bifos.assistant.chat.application.ModelTierService;
import com.bifos.assistant.chat.domain.ChatAttachment;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.domain.ModelChoice;
import com.bifos.assistant.chat.domain.type.ModelSelectionMode;
import com.bifos.assistant.chat.infra.ChatAttachmentRepository;
import com.bifos.assistant.chat.infra.ChatMessageRepository;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.chat.presentation.ChatController;
import com.bifos.assistant.chat.presentation.ChatDtos.ChooseModelRequest;
import com.bifos.assistant.chat.presentation.ChatDtos.ConversationView;
import com.bifos.assistant.chat.presentation.ChatEventStreams;
import com.bifos.assistant.hermes.HermesRunsClient;
import com.bifos.assistant.hermes.StubHermesRunsClient;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.hermes.dto.TokenUsage;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.infra.ExecutionEventRepository;
import com.bifos.assistant.user.application.UserDisplayNameService;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.domain.UserRole;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

/** 대화마다 고르는 모델과 effort 를 저장하고 돌려주는 것을 확인한다. 실행이 그 값을 쓰는 것은 따로 본다. */
@SpringBootTest
@ActiveProfiles("test")
@Import(ConversationModelChoiceTest.StubRuntime.class)
class ConversationModelChoiceTest {

    private static final byte[] IMAGE = "not really a png".getBytes(StandardCharsets.UTF_8);

    @TestConfiguration
    static class StubRuntime {
        @Bean
        @Primary
        StubHermesRunsClient stubHermesRunsClient() {
            return new StubHermesRunsClient();
        }
    }

    @Autowired
    ChatService chat;

    @Autowired
    AttachmentService attachments;

    @Autowired
    AttachmentProperties attachmentProperties;

    @Autowired
    ConversationAccess access;

    @Autowired
    AgentService agentService;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    ChatMessageRepository messages;

    @Autowired
    ChatAttachmentRepository attachmentRows;

    @Autowired
    AgentExecutionRepository executions;

    @Autowired
    ExecutionEventRepository executionEvents;

    @Autowired
    AgentRepository agents;

    @Autowired
    AppUserRepository users;

    @Autowired
    HermesRunsClient hermes;

    @Autowired
    TransactionTemplate transaction;

    @BeforeEach
    void reset() throws IOException {
        ((StubHermesRunsClient) hermes).reset();
        attachmentRows.deleteAll();
        executionEvents.deleteAll();
        executions.deleteAll();
        messages.deleteAll();
        conversations.deleteAll();
        agents.deleteAll();
        users.deleteAll();
        deleteTree(Path.of(attachmentProperties.root()).toAbsolutePath());
    }

    @Test
    @DisplayName("고른 모델과 effort 를 저장하고 목록 순서를 정하는 시각은 그대로다")
    void storesChosenModelAndEffortAndKeepsListOrderTimeUnchanged() {
        CurrentUser dad = member("choice-dad");
        Long id = chat.startEmpty(dad, "choice-dad").id();
        Instant before = conversations.findById(id).orElseThrow().updatedAt();

        chat.chooseModel(dad, id, new ModelChoice("openrouter", "example-model-small", "high"));

        Conversation stored = conversations.findById(id).orElseThrow();
        assertThat(stored.modelChoice()).isEqualTo(new ModelChoice("openrouter", "example-model-small", "high"));
        assertThat(stored.updatedAt()).as("updatedAt after choosing a model").isEqualTo(before);
    }

    @Test
    @DisplayName("경로는 바뀐 대화 한 줄을 돌려주고 목록도 같은 값을 싣는다")
    void pathReturnsChangedConversationRowAndListCarriesSameValues() {
        CurrentUser dad = member("choice-dad");
        Conversation created = chat.startEmpty(dad, "choice-dad");
        ChatController controller = chatController(dad);

        ConversationView chosen = controller.chooseModel(
                created.publicId(), new ChooseModelRequest("openrouter", "example-model-small", "max"));

        assertThat(chosen.id()).isEqualTo(created.publicId());
        assertThat(chosen.agentCode()).isEqualTo("choice-dad");
        assertThat(List.of(chosen.provider(), chosen.model(), chosen.reasoningEffort()))
                .containsExactly("openrouter", "example-model-small", "max");
        assertThat(controller.conversations()).singleElement().isEqualTo(chosen);
    }

    @Test
    @DisplayName("고르지 않은 대화와 모두 비워 보낸 대화는 기본값이다")
    void unchosenAndAllBlankConversationsUseDefault() {
        CurrentUser dad = member("choice-dad");
        Conversation created = chat.startEmpty(dad, "choice-dad");
        ChatController controller = chatController(dad);
        assertThat(controller.conversations()).singleElement().satisfies(it -> {
            assertThat(it.provider()).isNull();
            assertThat(it.model()).isNull();
            assertThat(it.reasoningEffort()).isNull();
        });
        chat.chooseModel(dad, created.id(), new ModelChoice("openrouter", "example-model-small", "low"));

        ConversationView cleared = controller.chooseModel(created.publicId(), new ChooseModelRequest(" ", "", null));

        assertThat(conversations.findById(created.id()).orElseThrow().modelChoice())
                .isEqualTo(ModelChoice.defaults());
        assertThat(ModelChoice.defaults().usesDefaultModel()).isTrue();
        assertThat(cleared.provider()).isNull();
        assertThat(cleared.model()).isNull();
        assertThat(cleared.reasoningEffort()).isNull();
    }

    @Test
    @DisplayName("provider 만 주거나 모르는 effort 를 주면 거절하고 저장된 값이 그대로다")
    void rejectsProviderOnlyOrUnknownEffortAndKeepsStoredValue() {
        CurrentUser dad = member("choice-dad");
        Conversation created = chat.startEmpty(dad, "choice-dad");
        ModelChoice saved = new ModelChoice("openrouter", "example-model-small", "medium");
        chat.chooseModel(dad, created.id(), saved);
        ChatController controller = chatController(dad);

        rejected(() -> controller.chooseModel(created.publicId(), new ChooseModelRequest("openrouter", null, null)));
        rejected(() ->
                controller.chooseModel(created.publicId(), new ChooseModelRequest(null, "example-model-small", null)));
        rejected(() -> controller.chooseModel(
                created.publicId(), new ChooseModelRequest("openrouter", "example-model-small", "extreme")));

        assertThat(conversations.findById(created.id()).orElseThrow().modelChoice())
                .isEqualTo(saved);
    }

    @Test
    @DisplayName("공백을 뗀 provider 나 모델이 열보다 길면 거절하고 저장된 값이 그대로다")
    void rejectsTrimmedProviderOrModelLongerThanColumnAndKeepsStoredValue() {
        CurrentUser dad = member("choice-dad");
        Conversation created = chat.startEmpty(dad, "choice-dad");
        ModelChoice saved = new ModelChoice("openrouter", "example-model-small", "medium");
        chat.chooseModel(dad, created.id(), saved);
        ChatController controller = chatController(dad);
        String longestProvider = "p".repeat(64);
        String longestModel = "m".repeat(128);

        rejected(() -> controller.chooseModel(
                created.publicId(), new ChooseModelRequest(longestProvider + "p", "example-model-small", null)));
        rejected(() -> controller.chooseModel(
                created.publicId(), new ChooseModelRequest("openrouter", longestModel + "m", null)));
        assertThat(conversations.findById(created.id()).orElseThrow().modelChoice())
                .isEqualTo(saved);

        ConversationView longest = controller.chooseModel(
                created.publicId(),
                new ChooseModelRequest("  " + longestProvider + " ", "\t" + longestModel + "\n", null));

        assertThat(List.of(longest.provider(), longest.model())).containsExactly(longestProvider, longestModel);
        assertThat(conversations.findById(created.id()).orElseThrow().modelChoice())
                .isEqualTo(new ModelChoice(longestProvider, longestModel, null));
    }

    @Test
    @DisplayName("저장된 값이 검증에 맞지 않아도 목록과 보내기가 그 값을 그대로 싣는다")
    void listAndSendCarryStoredValueEvenIfItFailsValidation() {
        CurrentUser dad = member("choice-dad");
        Conversation created = chat.startEmpty(dad, "choice-dad");
        transaction.executeWithoutResult(status -> conversations.chooseModelIfActive(
                created.id(), dad.id(), "openrouter", "example-model-small", "extreme", ModelSelectionMode.CUSTOM));
        ChatController controller = chatController(dad);
        ((StubHermesRunsClient) hermes)
                .willReturn(HermesRunResult.of(
                        "run-1", "sess-1", "completed", "네", "example-model-small", "openrouter", TokenUsage.empty()));

        assertThat(controller.conversations())
                .singleElement()
                .satisfies(it -> assertThat(List.of(it.provider(), it.model(), it.reasoningEffort()))
                        .containsExactly("openrouter", "example-model-small", "extreme"));
        chat.send(dad, created.id(), "안녕", null);

        assertThat(((StubHermesRunsClient) hermes).received())
                .singleElement()
                .satisfies(
                        command -> assertThat(List.of(command.provider(), command.model(), command.reasoningEffort()))
                                .containsExactly("openrouter", "example-model-small", "extreme"));
    }

    @Test
    @DisplayName("앞뒤 공백을 떼고 저장한다")
    void trimsSurroundingWhitespaceOnSave() {
        CurrentUser dad = member("choice-dad");
        Long id = chat.startEmpty(dad, "choice-dad").id();

        chat.chooseModel(dad, id, new ModelChoice("  openrouter ", "\texample-model-small\n", " xhigh "));

        assertThat(conversations.findById(id).orElseThrow().modelChoice())
                .isEqualTo(new ModelChoice("openrouter", "example-model-small", "xhigh"));
    }

    @Test
    @DisplayName("남의 대화와 지운 대화는 없는 대화와 같다")
    void othersAndDeletedConversationsAreSameAsMissing() {
        CurrentUser dad = member("choice-dad");
        CurrentUser kid = member("choice-kid");
        Long dadsId = chat.startEmpty(dad, "choice-dad").id();
        Long deletedId = chat.startEmpty(kid, "choice-kid").id();
        chat.delete(kid, deletedId);
        ModelChoice choice = new ModelChoice("openrouter", "example-model-small", "high");

        notFound(() -> chat.chooseModel(kid, dadsId, choice));
        notFound(() -> chat.chooseModel(kid, deletedId, choice));

        assertThat(conversations.findById(dadsId).orElseThrow().modelChoice()).isEqualTo(ModelChoice.defaults());
        assertThat(conversations.findById(deletedId).orElseThrow().modelChoice())
                .isEqualTo(ModelChoice.defaults());
    }

    @Test
    @DisplayName("흐름이 붙은 에이전트의 빈 대화에 모델을 고르고 사진은 보낼 때 거절한다")
    void choosesModelOnEmptyFlowAgentConversationAndRejectsImagesAtSend() {
        CurrentUser dad = member("choice-dad");
        Agent flowed = agents.findByCode("choice-dad").orElseThrow();
        flowed.assignFlow("research-and-build");
        agents.save(flowed);

        Long id = chat.startEmpty(dad, "choice-dad").id();
        chat.chooseModel(dad, id, new ModelChoice("openrouter", "example-model-small", null));
        ChatAttachment photo =
                attachments.upload(dad, id, "a.png", "image/png", IMAGE.length, new ByteArrayResource(IMAGE));

        rejected(() -> chat.send(dad, id, "사진 봐", null, List.of(photo.id())));

        assertThat(conversations.findById(id).orElseThrow().modelChoice())
                .isEqualTo(new ModelChoice("openrouter", "example-model-small", null));
        assertThat(messages.findByConversationIdOrderByIdAsc(id)).isEmpty();
        assertThat(attachmentRows.findById(photo.id()).orElseThrow().messageId())
                .isNull();
    }

    private CurrentUser member(String name) {
        AppUser user = users.save(AppUser.of(name + "@example.com", name, 1L, UserRole.MEMBER));
        agents.save(Agent.of(
                name,
                name,
                name,
                "http://agent-runtime.test/p/" + name,
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE,
                user.id()));
        return new CurrentUser(user.id(), user.email(), user.displayName(), user.groupId(), user.role());
    }

    private ChatController chatController(CurrentUser user) {
        CurrentUserProvider provider = mock(CurrentUserProvider.class);
        when(provider.require()).thenReturn(user);
        return new ChatController(
                chat,
                provider,
                new UserDisplayNameService(users),
                agentService,
                access,
                new ChatEventStreams(Duration.ofSeconds(20)),
                null,
                mock(ModelTierService.class));
    }

    private static void rejected(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    private static void notFound(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.CONVERSATION_NOT_FOUND));
    }

    private static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            for (Path each : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(each);
            }
        }
    }
}
