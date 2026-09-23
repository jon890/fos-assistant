package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.chat.application.AttachmentContent;
import com.bifos.assistant.chat.application.AttachmentProperties;
import com.bifos.assistant.chat.application.AttachmentService;
import com.bifos.assistant.chat.domain.ChatAttachment;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ChatAttachmentRepository;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.user.domain.UserRole;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** 사진을 받아 두고 돌려주는 규칙과 대화 주인 경계를 확인한다. */
@SpringBootTest
@ActiveProfiles("test")
class AttachmentServiceTest {

    private static final CurrentUser OWNER =
            new CurrentUser(9101L, "owner@example.com", "주인", 1L, UserRole.MEMBER);
    private static final CurrentUser STRANGER =
            new CurrentUser(9102L, "stranger@example.com", "남", 1L, UserRole.MEMBER);
    private static final byte[] IMAGE = "not really a png".getBytes(StandardCharsets.UTF_8);

    @Autowired AttachmentService service;
    @Autowired AttachmentProperties properties;
    @Autowired ChatAttachmentRepository attachments;
    @Autowired ConversationRepository conversations;

    private Path root;
    private Long mine;
    private Long theirs;

    @BeforeEach
    void 준비한다() throws IOException {
        attachments.deleteAll();
        root = Path.of(properties.root()).toAbsolutePath();
        deleteTree(root);
        mine = conversations.save(Conversation.startedBy(OWNER.id(), "내 대화", null)).id();
        theirs = conversations.save(Conversation.startedBy(STRANGER.id(), "남의 대화", null)).id();
    }

    @Test
    void 자기_대화에_올리면_행이_생기고_파일이_그_자리에_있다() throws IOException {
        ChatAttachment saved = upload(OWNER, mine, "image/png", IMAGE);

        ChatAttachment row = attachments.findById(saved.id()).orElseThrow();
        assertThat(row.storedName()).isEqualTo(saved.id() + ".png");
        assertThat(row.messageId()).isNull();
        assertThat(row.isVisible()).isTrue();
        Path file = root.resolve(String.valueOf(mine)).resolve(saved.id() + ".png");
        assertThat(file).exists();
        assertThat(Files.readAllBytes(file)).isEqualTo(IMAGE);
    }

    @Test
    void 남의_대화에_올리면_없는_대화로_거절하고_파일을_만들지_않는다() {
        assertCode(() -> upload(OWNER, theirs, "image/png", IMAGE), ErrorCode.CONVERSATION_NOT_FOUND);

        assertThat(attachments.findByConversationIdOrderByIdAsc(theirs)).isEmpty();
        assertThat(root.resolve(String.valueOf(theirs))).doesNotExist();
    }

    @Test
    void 받지_않는_형식은_거절하고_행을_만들지_않는다() {
        assertCode(() -> upload(OWNER, mine, "application/pdf", IMAGE), ErrorCode.VALIDATION_FAILED);

        assertThat(attachments.findByConversationIdOrderByIdAsc(mine)).isEmpty();
    }

    @Test
    void 한_장_상한을_넘으면_거절하고_행을_만들지_않는다() {
        byte[] tooLarge = new byte[(int) (properties.maxBytes() + 1)];

        assertCode(() -> upload(OWNER, mine, "image/jpeg", tooLarge), ErrorCode.VALIDATION_FAILED);

        assertThat(attachments.findByConversationIdOrderByIdAsc(mine)).isEmpty();
    }

    @Test
    void 묶이지_않은_사진이_상한만큼_있으면_한_장_더_올리지_못한다() {
        for (int i = 0; i < properties.maxFiles(); i++) {
            upload(OWNER, mine, "image/png", IMAGE);
        }

        assertCode(() -> upload(OWNER, mine, "image/png", IMAGE), ErrorCode.VALIDATION_FAILED);
        assertThat(attachments.findByConversationIdOrderByIdAsc(mine)).hasSize(properties.maxFiles());
    }

    @Test
    void 이미_보낸_사진은_세지_않아_새로_올릴_수_있다() {
        List<Long> sent = new ArrayList<>();
        for (int i = 0; i < properties.maxFiles(); i++) {
            sent.add(upload(OWNER, mine, "image/png", IMAGE).id());
        }
        service.attach(501L, mine, sent);

        ChatAttachment next = upload(OWNER, mine, "image/png", IMAGE);

        assertThat(attachments.findById(next.id())).isPresent();
    }

    @Test
    void 지운_첨부를_읽으면_있었다는_것만_알린다() {
        ChatAttachment saved = upload(OWNER, mine, "image/png", IMAGE);
        service.deleteByUser(OWNER, mine, saved.id());

        assertCode(() -> service.read(OWNER, mine, saved.id()), ErrorCode.ATTACHMENT_GONE);
    }

    @Test
    void 없는_번호를_읽으면_없는_대화와_같은_응답이다() {
        assertCode(() -> service.read(OWNER, mine, 987654321L), ErrorCode.CONVERSATION_NOT_FOUND);
    }

    @Test
    void 내_대화_번호에_남의_첨부_번호를_붙여도_읽지_못한다() {
        ChatAttachment others = upload(STRANGER, theirs, "image/png", IMAGE);

        assertCode(() -> service.read(OWNER, mine, others.id()), ErrorCode.CONVERSATION_NOT_FOUND);
        assertCode(() -> service.deleteByUser(OWNER, mine, others.id()), ErrorCode.CONVERSATION_NOT_FOUND);
        assertThat(attachments.findById(others.id()).orElseThrow().isVisible()).isTrue();
    }

    @Test
    void 자기_첨부는_올린_본문을_그대로_읽는다() throws IOException {
        ChatAttachment saved = upload(OWNER, mine, "image/webp", IMAGE);

        AttachmentContent content = service.read(OWNER, mine, saved.id());

        assertThat(content.contentType()).isEqualTo("image/webp");
        try (InputStream body = content.body()) {
            assertThat(body.readAllBytes()).isEqualTo(IMAGE);
        }
    }

    @Test
    void 사용자가_지우면_파일이_사라지고_행은_남는다() {
        ChatAttachment saved = upload(OWNER, mine, "image/gif", IMAGE);
        Path file = root.resolve(String.valueOf(mine)).resolve(saved.id() + ".gif");

        service.deleteByUser(OWNER, mine, saved.id());
        service.deleteByUser(OWNER, mine, saved.id());

        assertThat(file).doesNotExist();
        ChatAttachment row = attachments.findById(saved.id()).orElseThrow();
        assertThat(row.deletedAt()).isNotNull();
        assertThat(service.allOf(mine)).extracting(ChatAttachment::id).containsExactly(saved.id());
    }

    @Test
    void 파일을_쓰지_못하면_행을_남기지_않는다() throws IOException {
        // 대화 디렉터리 자리에 일반 파일을 두어 디렉터리를 만들지 못하게 한다.
        Files.createDirectories(root);
        Files.write(root.resolve(String.valueOf(mine)), new byte[] {1});

        assertThatThrownBy(() -> upload(OWNER, mine, "image/png", IMAGE)).isInstanceOf(ApiException.class);

        assertThat(attachments.findByConversationIdOrderByIdAsc(mine)).isEmpty();
    }

    @Test
    void 묶을_수_없는_첨부는_까닭을_가르지_않고_거절한다() {
        Long others = upload(STRANGER, theirs, "image/png", IMAGE).id();
        Long bound = upload(OWNER, mine, "image/png", IMAGE).id();
        service.attach(601L, mine, List.of(bound));
        Long deleted = upload(OWNER, mine, "image/png", IMAGE).id();
        service.deleteByUser(OWNER, mine, deleted);
        Long free = upload(OWNER, mine, "image/png", IMAGE).id();

        for (Long id : List.of(others, bound, deleted, 987654321L)) {
            assertCode(() -> service.requireAttachable(mine, List.of(free, id)), ErrorCode.VALIDATION_FAILED);
        }
        assertCode(() -> service.requireAttachable(mine, List.of(free, free)), ErrorCode.VALIDATION_FAILED);
        service.requireAttachable(mine, List.of(free));
    }

    @Test
    void 이미_묶인_첨부를_다시_묶으면_거절하고_처음_메시지를_가리킨다() {
        Long id = upload(OWNER, mine, "image/png", IMAGE).id();
        service.attach(701L, mine, List.of(id));

        assertCode(() -> service.attach(702L, mine, List.of(id)), ErrorCode.VALIDATION_FAILED);

        assertThat(attachments.findById(id).orElseThrow().messageId()).isEqualTo(701L);
    }

    private ChatAttachment upload(CurrentUser user, Long conversationId, String contentType, byte[] body) {
        return service.upload(
                user, conversationId, "photo", contentType, body.length, () -> new ByteArrayInputStream(body));
    }

    private static void assertCode(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo(expected));
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
