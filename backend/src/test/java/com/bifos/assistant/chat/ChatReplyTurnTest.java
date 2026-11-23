package com.bifos.assistant.chat;

import com.bifos.assistant.testsupport.TestPrimaryAgents;
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
import com.bifos.assistant.chat.artifact.application.ArtifactPutFiles;
import com.bifos.assistant.chat.artifact.application.ArtifactService;
import com.bifos.assistant.chat.artifact.application.GeneratedImageService;
import com.bifos.assistant.chat.artifact.domain.ChatArtifact;
import com.bifos.assistant.chat.artifact.infra.ArtifactStore;
import com.bifos.assistant.chat.artifact.infra.ChatArtifactRepository;
import com.bifos.assistant.chat.attachment.application.AttachmentService;
import com.bifos.assistant.chat.attachment.config.AttachmentProperties;
import com.bifos.assistant.chat.attachment.domain.ChatAttachment;
import com.bifos.assistant.chat.attachment.infra.ChatAttachmentRepository;
import com.bifos.assistant.chat.conversation.application.ConversationAccess;
import com.bifos.assistant.chat.conversation.application.SourceReadSummaries;
import com.bifos.assistant.chat.conversation.domain.ChatMessage;
import com.bifos.assistant.chat.conversation.domain.ReplyRef;
import com.bifos.assistant.chat.conversation.domain.type.MessageRole;
import com.bifos.assistant.chat.conversation.domain.type.SteerState;
import com.bifos.assistant.chat.conversation.infra.ChatMessageRepository;
import com.bifos.assistant.chat.delivery.application.AutoTurnNoticeStatuses;
import com.bifos.assistant.chat.modelchoice.application.ModelTierService;
import com.bifos.assistant.chat.reply.application.dto.ReplyPick;
import com.bifos.assistant.chat.turn.application.ChatService;
import com.bifos.assistant.chat.turn.presentation.ChatController;
import com.bifos.assistant.chat.turn.presentation.ChatDtos.MessageView;
import com.bifos.assistant.chat.turn.presentation.ChatDtos.ReplyImageView;
import com.bifos.assistant.chat.turn.presentation.ChatDtos.ReplyView;
import com.bifos.assistant.chat.turn.presentation.ChatEventStreams;
import com.bifos.assistant.hermes.StubHermesRunsClient;
import com.bifos.assistant.runtime.application.dto.RunImage;
import com.bifos.assistant.hermes.application.dto.HermesRunCommand;
import com.bifos.assistant.hermes.application.dto.HermesRunResult;
import com.bifos.assistant.hermes.application.port.HermesRunsClient;
import com.bifos.assistant.hermes.application.port.HermesToolsetClient;
import com.bifos.assistant.runtime.domain.TokenUsage;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.user.application.UserDisplayNameService;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 답장으로 집은 메시지와 그림이 검사를 거쳐 질문 줄에 남고, 실행 입력의 구역과 이미지로 가며, 이력의 상자로 보이는 것을 확인한다
 * (스펙 chat-reference REQ6~REQ9, REQ13~REQ15).
 */
@BackendIntegrationTest
class ChatReplyTurnTest {
    private static final Instant NOW = Instant.parse("2026-11-21T00:00:00Z");
    private static final String ANSWER = "MEDIA:/opt/data/cache/generated/images/a.png\n수채화 고양이를 그렸어요.\n창가에 앉아 있어요.\n\n셋째 줄";

    @Autowired
    TestPrimaryAgents primaries;

    @Autowired
    ChatService chat;

    @Autowired
    GeneratedImageService images;

    @Autowired
    ArtifactService artifactService;

    @Autowired
    ArtifactStore artifactStore;

    @Autowired
    ChatArtifactRepository artifactRows;

    @Autowired
    AttachmentService attachments;

    @Autowired
    AttachmentProperties attachmentProperties;

    @Autowired
    ChatAttachmentRepository attachmentRows;

    @Autowired
    ChatMessageRepository messages;

    @Autowired
    AppUserRepository users;

    @Autowired
    AgentRepository agents;

    @Autowired
    AgentService agentService;

    @Autowired
    ConversationAccess access;

    @Autowired
    HermesRunsClient hermes;

    @Autowired
    HermesToolsetClient toolsets;

    @Autowired
    JdbcTemplate jdbc;

    private CurrentUser dad;
    private String dadAgent;
    private Long conversationId;
    private ChatMessage answer;


    @BeforeEach
    void setUp() {
        Mockito.when(toolsets.readEnabled(ArgumentMatchers.any(), ArgumentMatchers.any()))
            .thenReturn(List.of("fos-assistant", "fos-attachments"));
        stub().reset();
        stub().willReturn(HermesRunResult.of("run-1", "sess-1", "completed", "다시 그렸어요", "m", "p", TokenUsage.empty()));
        dad = member("reply-" + UUID.randomUUID() + "@example.com");
        dadAgent = "reply-" + UUID.randomUUID();
        agentOf(dad, dadAgent);
        conversationId = chat.startEmpty(dad, dadAgent).id();
        answer = messages.save(ChatMessage.fromAssistant(conversationId, ANSWER, null, NOW));
    }


    @AfterEach
    void tearDown() {
        artifactRows.deleteAll(artifactRows.findAll().stream().filter(row -> conversationId.equals(row.conversationId())).toList());
        artifactStore.deleteFolder(conversationId);
    }


    @Test
    @DisplayName("답 글을 집어 보내면 질문 줄에 칸이 차고 실행 입력에 쓴 사람과 그림 경로를 가린 전문이 질문 앞에 따로 들어간다")
    void quotesWholeAnswerBeforeQuestion() {
        chat.stream(dad, conversationId, "이걸 더 짧게 써 줘", null, List.of(), null, new ReplyPick(answer.id(), null, null), event -> {
        });

        ChatMessage question = lastUser();
        assertThat(question.reply()).isEqualTo(new ReplyRef(answer.id(), null, null));
        assertThat(question.content()).as("저장하는 질문은 사용자가 쓴 글 그대로다").isEqualTo("이걸 더 짧게 써 줘");
        String input = stub().received().getLast().input();
        assertThat(input).contains("[사용자가 집은 메시지]", "쓴 사람: 비서", "<quoted-message>\n수채화 고양이를 그렸어요.")
            .doesNotContain("/cache/generated/images/");
        assertThat(input.indexOf("[사용자가 집은 메시지]")).isLessThan(input.indexOf("이걸 더 짧게 써 줘"));
    }


    @Test
    @DisplayName("그림 한 장을 집으면 그 그림이 이름표 「집은 그림 1」 로 입력 이미지에 들고 구역에 generated 가리킴이 있다")
    void picksOneGeneratedImage() throws IOException {
        ChatArtifact first = generated("call_1", "수채화 고양이", png(Color.ORANGE));
        generated("call_2", "수채화 강아지", png(Color.BLUE));
        artifactService.recordTurn(conversationId, answer.id(), NOW);

        chat.stream(dad, conversationId, "이 그림을 수채화로 바꿔 줘", null, List.of(), null,
            new ReplyPick(answer.id(), null, first.path()), event -> {
            });

        HermesRunCommand command = stub().received().getLast();
        assertThat(command.images()).extracting(RunImage::label).containsExactly("집은 그림 1");
        assertThat(command.images().getFirst().dataUrl()).startsWith("data:image/jpeg;base64,");
        String section = command.input().substring(command.input().indexOf("[사용자가 집은 메시지]"));
        assertThat(section).contains("generated:수채화 고양이", "입력 이미지 「집은 그림 1」").doesNotContain("수채화 강아지");
        assertThat(lastUser().reply()).isEqualTo(new ReplyRef(answer.id(), null, first.id()));
    }


    @Test
    @DisplayName("그림이 둘인 답을 통째로 집으면 둘 다 들고 어느 그림인지 되묻는 지침이 붙는다")
    void wholeAnswerWithTwoImagesAsksWhichOne() throws IOException {
        generated("call_1", "수채화 고양이", png(Color.ORANGE));
        generated("call_2", "수채화 강아지", png(Color.BLUE));
        artifactService.recordTurn(conversationId, answer.id(), NOW);

        chat.stream(dad, conversationId, "이걸 참고해서 다시 그려 줘", null, List.of(), null, new ReplyPick(answer.id(), null, null), event -> {
        });

        HermesRunCommand command = stub().received().getLast();
        assertThat(command.images()).extracting(RunImage::label).containsExactly("집은 그림 1", "집은 그림 2");
        assertThat(command.input()).contains("먼저 되묻는다");
    }


    @Test
    @DisplayName("내 메시지의 사진 한 장을 집으면 그 사진이 attachment 가리킴과 함께 들고 새 사진 순번을 받지 않는다")
    void picksOnePhoto() throws IOException {
        byte[] red = png(Color.RED);
        ChatAttachment photo = attachments.upload(dad, conversationId, "신발.png", "image/png", red.length, resource(red));
        chat.stream(dad, conversationId, "신발 사진이야", null, List.of(photo.id()), null, null, event -> {
        });
        ChatMessage mine = lastUser();

        chat.stream(dad, conversationId, "이 신발 색만 바꿔 줘", null, List.of(), null, new ReplyPick(mine.id(), photo.id(), null), event -> {
        });

        HermesRunCommand command = stub().received().getLast();
        assertThat(command.images()).extracting(RunImage::label).containsExactly("집은 그림 1");
        assertThat(command.input()).contains("쓴 사람: 사용자", "1번째 사진(attachment:" + photo.id() + ")");
    }


    @Test
    @DisplayName("파일이 지워진 그림을 집으면 409 이고 질문을 저장하지 않는다")
    void deletedImageIsUnavailable() throws IOException {
        ChatArtifact image = generated("call_1", "수채화 고양이", png(Color.ORANGE));
        artifactService.recordTurn(conversationId, answer.id(), NOW);
        artifactStore.deleteFolder(conversationId);

        assertCode(() -> send(new ReplyPick(answer.id(), null, image.path())), ErrorCode.REPLY_SOURCE_UNAVAILABLE);
        assertThat(userMessages()).isEmpty();
    }


    @Test
    @DisplayName("다른 대화의 메시지, 남의 메시지, 알림 줄, 그 메시지의 것이 아닌 그림은 같은 404 이고 저장하지 않는다")
    void foreignTargetsAreNotFound() {
        Long other = chat.startEmpty(dad, dadAgent).id();
        ChatMessage elsewhere = messages.save(ChatMessage.fromAssistant(other, "다른 대화", null, NOW));
        CurrentUser mom = member("reply-mom-" + UUID.randomUUID() + "@example.com");
        String momAgent = "reply-mom-" + UUID.randomUUID();
        agentOf(mom, momAgent);
        Long momsConversation = chat.startEmpty(mom, momAgent).id();
        ChatMessage moms = messages.save(ChatMessage.fromAssistant(momsConversation, "엄마 대화", null, NOW));
        ChatMessage notice = messages.save(ChatMessage.fromSystem(conversationId, "알림", NOW));

        assertCode(() -> send(new ReplyPick(elsewhere.id(), null, null)), ErrorCode.MESSAGE_NOT_FOUND);
        assertCode(() -> send(new ReplyPick(moms.id(), null, null)), ErrorCode.MESSAGE_NOT_FOUND);
        assertCode(() -> send(new ReplyPick(notice.id(), null, null)), ErrorCode.MESSAGE_NOT_FOUND);
        assertCode(() -> send(new ReplyPick(answer.id(), null, "generated/없는 그림.png")), ErrorCode.MESSAGE_NOT_FOUND);
        assertThat(userMessages()).isEmpty();
    }


    @Test
    @DisplayName("다른 메시지에 묶인 사진 번호와 끼워 넣은 글은 404, 보관 기간이 지난 사진은 409 이고 저장하지 않는다")
    void photoAndSteerTargets() throws IOException {
        byte[] red = png(Color.RED);
        ChatAttachment photo = attachments.upload(dad, conversationId, "신발.png", "image/png", red.length, resource(red));
        chat.stream(dad, conversationId, "신발 사진이야", null, List.of(photo.id()), null, null, event -> {
        });
        ChatMessage mine = lastUser();
        ChatMessage steer = messages.save(ChatMessage.fromSteer(
            conversationId, dad.id(), "끼운 글", null, SteerState.DELIVERED, null, NOW));
        int before = userMessages().size();

        assertCode(() -> send(new ReplyPick(answer.id(), photo.id(), null)), ErrorCode.MESSAGE_NOT_FOUND);
        assertCode(() -> send(new ReplyPick(steer.id(), null, null)), ErrorCode.MESSAGE_NOT_FOUND);
        jdbc.update("update chat_attachment set expires_at = ? where id = ?", Timestamp.from(NOW.minusSeconds(60)), photo.id());
        assertCode(() -> send(new ReplyPick(mine.id(), photo.id(), null)), ErrorCode.REPLY_SOURCE_UNAVAILABLE);
        assertThat(userMessages()).hasSize(before);
    }


    @Test
    @DisplayName("맡김과 함께, 대화 없이, 사진과 그림을 함께 집은 답장은 400 이다")
    void invalidCombinationsAreRejected() {
        assertCode(() -> chat.stream(dad, conversationId, "찾아 줘", null, List.of(), "someone",
            new ReplyPick(answer.id(), null, null), event -> {
            }), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> chat.stream(dad, null, "새 대화", dadAgent, List.of(), null,
            new ReplyPick(answer.id(), null, null), event -> {
            }), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> send(new ReplyPick(answer.id(), 1L, "generated/a.png")), ErrorCode.VALIDATION_FAILED);
    }


    @Test
    @DisplayName("다시 만들면 같은 구역이 다시 들고, 그림 파일을 지운 뒤에도 실패하지 않고 쓸 수 없다고 적는다")
    void regenerateKeepsReplyAndToleratesDeletedImage() throws IOException {
        ChatArtifact image = generated("call_1", "수채화 고양이", png(Color.ORANGE));
        artifactService.recordTurn(conversationId, answer.id(), NOW);
        send(new ReplyPick(answer.id(), null, image.path()));

        chat.regenerate(dad, conversationId, event -> {
        });
        assertThat(stub().received().getLast().input()).contains("[사용자가 집은 메시지]", "입력 이미지 「집은 그림 1」");

        artifactStore.deleteFolder(conversationId);
        chat.regenerate(dad, conversationId, event -> {
        });
        HermesRunCommand command = stub().received().getLast();
        assertThat(command.images()).isEmpty();
        assertThat(command.input()).contains("generated:수채화 고양이", "쓸 수 없다");
    }


    @Test
    @DisplayName("이력의 답장 상자에 가린 글의 앞 두 줄과 집은 그림이 있고, 파일을 지우면 지운 표시가 된다")
    void historyShowsReplyBox() throws IOException {
        ChatArtifact image = generated("call_1", "수채화 고양이", png(Color.ORANGE));
        artifactService.recordTurn(conversationId, answer.id(), NOW);
        send(new ReplyPick(answer.id(), null, image.path()));

        ReplyView reply = replyOfLastUser();
        assertThat(reply.messageId()).isEqualTo(answer.id());
        assertThat(reply.role()).isEqualTo("ASSISTANT");
        assertThat(reply.excerpt()).isEqualTo("수채화 고양이를 그렸어요.\n창가에 앉아 있어요.");
        assertThat(reply.images()).containsExactly(new ReplyImageView("GENERATED", image.path(), null, false));

        jdbc.update("update chat_artifact set deleted_at = ? where id = ?", Timestamp.from(NOW), image.id());
        assertThat(replyOfLastUser().images()).extracting(ReplyImageView::deleted).containsExactly(true);
    }


    private void send(ReplyPick pick) {
        chat.stream(dad, conversationId, "이걸 참고해 줘", null, List.of(), null, pick, event -> {
        });
    }


    private ReplyView replyOfLastUser() {
        List<MessageView> history = controller().messages(access.requireOwn(dad, conversationId).publicId());
        return history.stream().filter(it -> "USER".equals(it.role())).toList().getLast().reply();
    }


    private ChatController controller() {
        CurrentUserProvider provider = mock(CurrentUserProvider.class);
        when(provider.require()).thenReturn(dad);
        return new ChatController(
            chat,
            provider,
            new UserDisplayNameService(users),
            agentService,
            access,
            new ChatEventStreams(Duration.ofSeconds(20)),
            mock(ModelTierService.class),
            List.of(),
            mock(SourceReadSummaries.class),
            new AutoTurnNoticeStatuses(List.of()),
            images, mock(ArtifactPutFiles.class));
    }


    private ChatArtifact generated(String call, String title, byte[] bytes) {
        images.save(conversationId, dad.id(), "key-" + conversationId + "-" + call, title, "지시문", bytes);
        return artifactRows.findAll().stream()
            .filter(row -> conversationId.equals(row.conversationId()) && row.path().startsWith("generated/" + title + "."))
            .findFirst()
            .orElseThrow();
    }


    private ChatMessage lastUser() {
        return userMessages().getLast();
    }


    private List<ChatMessage> userMessages() {
        return messages.findByConversationIdOrderByIdAsc(conversationId).stream()
            .filter(it -> it.role() == MessageRole.USER)
            .toList();
    }


    private static void assertCode(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run)
            .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo(code));
    }


    private StubHermesRunsClient stub() {
        return (StubHermesRunsClient)hermes;
    }


    private CurrentUser member(String email) {
        AppUser user = users.save(AppUser.of(email, email, 1L, UserRole.MEMBER));
        return new CurrentUser(user.id(), user.email(), user.displayName(), user.groupId(), user.role());
    }


    private void agentOf(CurrentUser owner, String code) {
        primaries.make(owner, agents.save(Agent.of(
            code, code, code, "http://agent-runtime.test/p/" + code,
            CostMode.SUBSCRIPTION, CredentialScope.SHARED_HOUSEHOLD, AgentVisibility.PRIVATE, owner.id())));
    }


    private static ByteArrayResource resource(byte[] bytes) {
        return new ByteArrayResource(bytes);
    }


    private static byte[] png(Color color) throws IOException {
        BufferedImage image = new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(color);
        graphics.fillRect(0, 0, 64, 48);
        graphics.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
