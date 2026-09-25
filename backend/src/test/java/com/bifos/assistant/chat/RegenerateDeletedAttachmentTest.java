package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.agent.application.AgentModelSelector;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentVisibility;
import com.bifos.assistant.agent.domain.CostMode;
import com.bifos.assistant.agent.domain.CredentialScope;
import com.bifos.assistant.agent.domain.ModelOption;
import com.bifos.assistant.agent.infra.AgentModelOptionRepository;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.application.ChatService;
import com.bifos.assistant.chat.domain.ChatAttachment;
import com.bifos.assistant.chat.domain.MessageRole;
import com.bifos.assistant.chat.infra.ChatAttachmentRepository;
import com.bifos.assistant.chat.infra.ChatMessageRepository;
import com.bifos.assistant.hermes.HermesRunsClient;
import com.bifos.assistant.hermes.StubHermesRunsClient;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.hermes.dto.TokenUsage;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.domain.UserRole;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/** 보관 기간이 지나 사라진 사진은 다시 생성 Hermes 입력에 넣지 않는다. */
@SpringBootTest
@ActiveProfiles("test")
@Import(RegenerateDeletedAttachmentTest.StubRuntime.class)
class RegenerateDeletedAttachmentTest {

    @TestConfiguration
    static class StubRuntime {
        @Bean @Primary StubHermesRunsClient stubHermesRunsClient() { return new StubHermesRunsClient(); }
    }

    @Autowired ChatService chat;
    @Autowired AppUserRepository users;
    @Autowired AgentRepository agents;
    @Autowired AgentModelSelector modelSelector;
    @Autowired AgentModelOptionRepository modelOptions;
    @Autowired ChatMessageRepository messages;
    @Autowired ChatAttachmentRepository attachments;
    @Autowired AgentExecutionRepository executions;
    @Autowired HermesRunsClient hermes;

    @BeforeEach
    void 준비한다() {
        stub().reset();
        attachments.deleteAll();
        executions.deleteAll();
        messages.deleteAll();
        modelOptions.deleteAll();
        agents.deleteAll();
        users.deleteAll();
    }

    @Test
    @Transactional
    void 지워진_사진은_다시_생성_Hermes_입력에서_뺀다() {
        CurrentUser dad = member();
        stub().willReturnInOrder(
                HermesRunResult.of("first", "session", "completed", "첫 답", "m", "p", TokenUsage.empty()),
                HermesRunResult.of("again", "session", "completed", "새 답", "m", "p", TokenUsage.empty()));

        var first = chat.send(dad, null, "사진을 설명해 줘", "dad");
        var question = messages.findByConversationIdOrderByIdAsc(first.conversationId()).stream()
                .filter(message -> message.role() == MessageRole.USER).findFirst().orElseThrow();
        ChatAttachment attachment = attachments.save(ChatAttachment.of(
                first.conversationId(), dad.id(), "지운.png", "image/png", 1, Instant.now().plusSeconds(1)));
        attachment.nameStoredFile(attachment.id() + ".png");
        attachments.save(attachment);
        attachments.attachToMessage(question.id(), first.conversationId(), java.util.List.of(attachment.id()));
        attachment = attachments.findById(attachment.id()).orElseThrow();
        attachment.markDeleted(Instant.now());
        attachments.save(attachment);

        chat.regenerate(dad, first.conversationId(), event -> {});

        assertThat(stub().received()).hasSize(2);
        assertThat(stub().received().get(1).input()).isEqualTo("사진을 설명해 줘");
    }

    private CurrentUser member() {
        AppUser user = users.save(AppUser.of("deleted-photo@example.com", "dad", 1L, UserRole.MEMBER));
        Agent agent = agents.save(Agent.of("dad", "dad", "dad", "http://agent-runtime.test/p/dad",
                "anthropic", "example-model-large", CostMode.SUBSCRIPTION, CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE, user.id()));
        modelSelector.seedFirst(agent, new ModelOption("anthropic", "example-model-large"));
        return new CurrentUser(user.id(), user.email(), user.displayName(), user.familyId(), user.role());
    }

    private StubHermesRunsClient stub() { return (StubHermesRunsClient) hermes; }
}
