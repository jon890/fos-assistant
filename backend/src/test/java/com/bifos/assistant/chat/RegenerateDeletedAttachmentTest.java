package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.application.ArtifactService;
import com.bifos.assistant.chat.application.ChatService;
import com.bifos.assistant.chat.domain.ChatAttachment;
import com.bifos.assistant.chat.domain.type.MessageRole;
import com.bifos.assistant.chat.infra.ChatAttachmentRepository;
import com.bifos.assistant.chat.infra.ChatMessageRepository;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.hermes.HermesRunsClient;
import com.bifos.assistant.hermes.StubHermesRunsClient;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.hermes.dto.TokenUsage;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
        @Bean
        @Primary
        StubHermesRunsClient stubHermesRunsClient() {
            return new StubHermesRunsClient();
        }
    }

    @Autowired
    ChatService chat;
    /** 결과물 폴더 단락의 문구는 {@code ArtifactTest} 가 글자 그대로 견준다. 여기서는 그 단락을 받아 쓴다. */
    @Autowired
    ArtifactService artifactService;

    @Autowired
    AppUserRepository users;

    @Autowired
    AgentRepository agents;

    @Autowired
    ChatMessageRepository messages;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    ChatAttachmentRepository attachments;

    @Autowired
    AgentExecutionRepository executions;

    @Autowired
    HermesRunsClient hermes;

    @BeforeEach
    void setUp() {
        stub().reset();
        attachments.deleteAll();
        executions.deleteAll();
        messages.deleteAll();
        agents.deleteAll();
        users.deleteAll();
    }

    @Test
    @DisplayName("지워진 사진은 다시 생성 Hermes 입력에서 뺀다")
    @Transactional
    void dropsRemovedImagesFromRegenerationHermesInput() {
        CurrentUser dad = member();
        stub().willReturnInOrder(
                        HermesRunResult.of("first", "session", "completed", "첫 답", "m", "p", TokenUsage.empty()),
                        HermesRunResult.of("again", "session", "completed", "새 답", "m", "p", TokenUsage.empty()));

        var first = chat.send(dad, null, "사진을 설명해 줘", "dad");
        var question = messages.findByConversationIdOrderByIdAsc(first.conversationId()).stream()
                .filter(message -> message.role() == MessageRole.USER)
                .findFirst()
                .orElseThrow();
        ChatAttachment attachment = attachments.save(ChatAttachment.of(
                first.conversationId(),
                dad.id(),
                "지운.png",
                "image/png",
                1,
                Instant.now().plusSeconds(1),
                Instant.now()));
        attachment.nameStoredFile(attachment.id() + ".png");
        attachments.save(attachment);
        attachments.attachToMessageAtPosition(question.id(), first.conversationId(), attachment.id(), 0);
        attachment = attachments.findById(attachment.id()).orElseThrow();
        attachment.markDeleted(Instant.now());
        attachments.save(attachment);

        chat.regenerate(dad, first.conversationId(), event -> {});

        assertThat(stub().received()).hasSize(2);
        // 지운 사진의 단락은 빠지고 결과물 폴더 단락만 사용자가 쓴 글 앞에 붙는다.
        assertThat(stub().received().get(1).input())
                .isEqualTo(artifactService.agentPreamble(
                                conversations.findById(first.conversationId()).orElseThrow()) + "사진을 설명해 줘");
    }

    @Test
    @DisplayName("중간 사진을 지워도 다시 생성 입력은 position 순서와 비어 있는 순번을 지킨다")
    @Transactional
    void regenerationKeepsPositionOrderAndOrdinalGapAfterMiddlePhotoDeletion() {
        CurrentUser dad = member();
        stub().willReturnInOrder(
                        HermesRunResult.of("first", "session", "completed", "첫 답", "m", "p", TokenUsage.empty()),
                        HermesRunResult.of("again", "session", "completed", "새 답", "m", "p", TokenUsage.empty()));
        var first = chat.send(dad, null, "사진을 설명해 줘", "dad");
        var question = messages.findByConversationIdOrderByIdAsc(first.conversationId()).stream()
                .filter(message -> message.role() == MessageRole.USER)
                .findFirst()
                .orElseThrow();

        ChatAttachment firstPhoto = createAttachment(first.conversationId(), dad, "first.png");
        ChatAttachment middlePhoto = createAttachment(first.conversationId(), dad, "middle.png");
        ChatAttachment lastPhoto = createAttachment(first.conversationId(), dad, "last.png");
        attachments.attachToMessageAtPosition(question.id(), first.conversationId(), lastPhoto.id(), 0);
        attachments.attachToMessageAtPosition(question.id(), first.conversationId(), middlePhoto.id(), 1);
        attachments.attachToMessageAtPosition(question.id(), first.conversationId(), firstPhoto.id(), 2);
        assertThat(firstPhoto.id()).isLessThan(lastPhoto.id());
        ChatAttachment deletedMiddle = attachments.findById(middlePhoto.id()).orElseThrow();
        deletedMiddle.markDeleted(Instant.now());
        attachments.save(deletedMiddle);

        chat.regenerate(dad, first.conversationId(), event -> {});

        String input = stub().received().get(1).input();
        assertThat(input)
                .contains("- 1번째 사진: " + lastPhoto.storedName())
                .contains("- 3번째 사진: " + firstPhoto.storedName())
                .doesNotContain("2번째 사진", deletedMiddle.storedName());
        assertThat(input.indexOf(lastPhoto.storedName())).isLessThan(input.indexOf(firstPhoto.storedName()));
    }

    private ChatAttachment createAttachment(Long conversationId, CurrentUser user, String name) {
        ChatAttachment attachment = attachments.save(ChatAttachment.of(
                conversationId, user.id(), name, "image/png", 1, Instant.now().plusSeconds(1), Instant.now()));
        attachment.nameStoredFile(attachment.id() + ".png");
        attachments.save(attachment);
        return attachment;
    }

    private CurrentUser member() {
        AppUser user = users.save(AppUser.of("deleted-photo@example.com", "dad", 1L, UserRole.MEMBER, Instant.now()));
        agents.save(Agent.of(
                "dad",
                "dad",
                "dad",
                "http://agent-runtime.test/p/dad",
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE,
                user.id(),
                Instant.now()));
        return new CurrentUser(user.id(), user.email(), user.displayName(), user.groupId(), user.role());
    }

    private StubHermesRunsClient stub() {
        return (StubHermesRunsClient) hermes;
    }
}
