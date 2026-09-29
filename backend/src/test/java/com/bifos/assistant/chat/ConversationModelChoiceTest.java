package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.application.AgentModelSelector;
import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentVisibility;
import com.bifos.assistant.agent.domain.CostMode;
import com.bifos.assistant.agent.domain.CredentialScope;
import com.bifos.assistant.agent.domain.ModelOption;
import com.bifos.assistant.agent.infra.AgentModelOptionRepository;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.application.AttachmentProperties;
import com.bifos.assistant.chat.application.AttachmentService;
import com.bifos.assistant.chat.application.ChatService;
import com.bifos.assistant.chat.application.ConversationAccess;
import com.bifos.assistant.chat.domain.ChatAttachment;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.domain.ModelChoice;
import com.bifos.assistant.chat.infra.ChatAttachmentRepository;
import com.bifos.assistant.chat.infra.ChatMessageRepository;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.chat.presentation.ChatController;
import com.bifos.assistant.chat.presentation.ChatDtos.ChooseModelRequest;
import com.bifos.assistant.chat.presentation.ChatDtos.ConversationView;
import com.bifos.assistant.chat.presentation.ChatEventStreams;
import com.bifos.assistant.hermes.HermesRunsClient;
import com.bifos.assistant.hermes.StubHermesRunsClient;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.infra.ExecutionEventRepository;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.test.context.ActiveProfiles;

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

    @Autowired ChatService chat;
    @Autowired AttachmentService attachments;
    @Autowired AttachmentProperties attachmentProperties;
    @Autowired ConversationAccess access;
    @Autowired AgentService agentService;
    @Autowired ConversationRepository conversations;
    @Autowired ChatMessageRepository messages;
    @Autowired ChatAttachmentRepository attachmentRows;
    @Autowired AgentExecutionRepository executions;
    @Autowired ExecutionEventRepository executionEvents;
    @Autowired AgentRepository agents;
    @Autowired AgentModelSelector modelSelector;
    @Autowired AgentModelOptionRepository modelOptions;
    @Autowired AppUserRepository users;
    @Autowired HermesRunsClient hermes;

    @BeforeEach
    void reset() throws IOException {
        ((StubHermesRunsClient) hermes).reset();
        attachmentRows.deleteAll();
        executionEvents.deleteAll();
        executions.deleteAll();
        messages.deleteAll();
        conversations.deleteAll();
        modelOptions.deleteAll();
        agents.deleteAll();
        users.deleteAll();
        deleteTree(Path.of(attachmentProperties.root()).toAbsolutePath());
    }

    @Test
    void 고른_모델과_effort_를_저장하고_목록_순서를_정하는_시각은_그대로다() {
        CurrentUser dad = member("choice-dad");
        Long id = chat.startEmpty(dad, "choice-dad").id();
        Instant before = conversations.findById(id).orElseThrow().updatedAt();

        chat.chooseModel(dad, id, new ModelChoice("openrouter", "example-model-small", "high"));

        Conversation stored = conversations.findById(id).orElseThrow();
        assertThat(stored.modelChoice())
                .isEqualTo(new ModelChoice("openrouter", "example-model-small", "high"));
        assertThat(stored.updatedAt()).as("updatedAt after choosing a model").isEqualTo(before);
    }

    @Test
    void 경로는_바뀐_대화_한_줄을_돌려주고_목록도_같은_값을_싣는다() {
        CurrentUser dad = member("choice-dad");
        Conversation created = chat.startEmpty(dad, "choice-dad");
        ChatController controller = chatController(dad);

        ConversationView chosen = controller.chooseModel(created.publicId(),
                new ChooseModelRequest("openrouter", "example-model-small", "max"));

        assertThat(chosen.id()).isEqualTo(created.publicId());
        assertThat(chosen.agentCode()).isEqualTo("choice-dad");
        assertThat(List.of(chosen.provider(), chosen.model(), chosen.reasoningEffort()))
                .containsExactly("openrouter", "example-model-small", "max");
        assertThat(controller.conversations()).singleElement().isEqualTo(chosen);
    }

    @Test
    void 고르지_않은_대화와_모두_비워_보낸_대화는_기본값이다() {
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
    void provider_만_주거나_모르는_effort_를_주면_거절하고_저장된_값이_그대로다() {
        CurrentUser dad = member("choice-dad");
        Conversation created = chat.startEmpty(dad, "choice-dad");
        ModelChoice saved = new ModelChoice("openrouter", "example-model-small", "medium");
        chat.chooseModel(dad, created.id(), saved);
        ChatController controller = chatController(dad);

        rejected(() -> controller.chooseModel(created.publicId(), new ChooseModelRequest("openrouter", null, null)));
        rejected(() -> controller.chooseModel(created.publicId(), new ChooseModelRequest(null, "example-model-small", null)));
        rejected(() -> controller.chooseModel(created.publicId(),
                new ChooseModelRequest("openrouter", "example-model-small", "extreme")));

        assertThat(conversations.findById(created.id()).orElseThrow().modelChoice()).isEqualTo(saved);
    }

    @Test
    void 앞뒤_공백을_떼고_저장한다() {
        CurrentUser dad = member("choice-dad");
        Long id = chat.startEmpty(dad, "choice-dad").id();

        chat.chooseModel(dad, id, new ModelChoice("  openrouter ", "\texample-model-small\n", " xhigh "));

        assertThat(conversations.findById(id).orElseThrow().modelChoice())
                .isEqualTo(new ModelChoice("openrouter", "example-model-small", "xhigh"));
    }

    @Test
    void 남의_대화와_지운_대화는_없는_대화와_같다() {
        CurrentUser dad = member("choice-dad");
        CurrentUser kid = member("choice-kid");
        Long dadsId = chat.startEmpty(dad, "choice-dad").id();
        Long deletedId = chat.startEmpty(kid, "choice-kid").id();
        chat.delete(kid, deletedId);
        ModelChoice choice = new ModelChoice("openrouter", "example-model-small", "high");

        notFound(() -> chat.chooseModel(kid, dadsId, choice));
        notFound(() -> chat.chooseModel(kid, deletedId, choice));

        assertThat(conversations.findById(dadsId).orElseThrow().modelChoice()).isEqualTo(ModelChoice.defaults());
        assertThat(conversations.findById(deletedId).orElseThrow().modelChoice()).isEqualTo(ModelChoice.defaults());
    }

    @Test
    void 흐름이_붙은_에이전트의_빈_대화에_모델을_고르고_사진은_보낼_때_거절한다() {
        CurrentUser dad = member("choice-dad");
        Agent flowed = agents.findByCode("choice-dad").orElseThrow();
        flowed.assignFlow("research-and-build");
        agents.save(flowed);

        Long id = chat.startEmpty(dad, "choice-dad").id();
        chat.chooseModel(dad, id, new ModelChoice("openrouter", "example-model-small", null));
        ChatAttachment photo = attachments.upload(
                dad, id, "a.png", "image/png", IMAGE.length, new ByteArrayResource(IMAGE));

        rejected(() -> chat.send(dad, id, "사진 봐", null, List.of(photo.id())));

        assertThat(conversations.findById(id).orElseThrow().modelChoice())
                .isEqualTo(new ModelChoice("openrouter", "example-model-small", null));
        assertThat(messages.findByConversationIdOrderByIdAsc(id)).isEmpty();
        assertThat(attachmentRows.findById(photo.id()).orElseThrow().messageId()).isNull();
    }

    private CurrentUser member(String name) {
        AppUser user = users.save(AppUser.of(name + "@example.com", name, 1L, UserRole.MEMBER));
        Agent agent = agents.save(Agent.of(name, name, name,
                "http://agent-runtime.test/p/" + name, "anthropic", "example-model-large",
                CostMode.SUBSCRIPTION, CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE, user.id()));
        modelSelector.seedFirst(agent, new ModelOption("anthropic", "example-model-large"));
        return new CurrentUser(user.id(), user.email(), user.displayName(), user.groupId(), user.role());
    }

    private ChatController chatController(CurrentUser user) {
        CurrentUserProvider provider = mock(CurrentUserProvider.class);
        when(provider.require()).thenReturn(user);
        return new ChatController(chat, provider, users, agentService, access,
                new ChatEventStreams(Duration.ofSeconds(20)));
    }

    private static void rejected(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    private static void notFound(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.CONVERSATION_NOT_FOUND));
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
