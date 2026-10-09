package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import com.bifos.assistant.chat.application.AttachmentContent;
import com.bifos.assistant.chat.application.AttachmentService;
import com.bifos.assistant.chat.application.ConversationWriter;
import com.bifos.assistant.chat.domain.ChatAttachment;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.AttachmentProperties;
import com.bifos.assistant.chat.infra.AttachmentStore;
import com.bifos.assistant.chat.infra.ChatAttachmentRepository;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 사진을 받아 두고 돌려주는 규칙과 대화 주인 경계를 확인한다. */
@BackendIntegrationTest
class AttachmentServiceTest {

    private static final CurrentUser OWNER = new CurrentUser(9101L, "owner@example.com", "주인", 1L, UserRole.MEMBER);
    private static final CurrentUser STRANGER =
            new CurrentUser(9102L, "stranger@example.com", "남", 1L, UserRole.MEMBER);
    private static final byte[] IMAGE = "not really a png".getBytes(StandardCharsets.UTF_8);

    @Autowired
    AttachmentService service;

    @Autowired
    AttachmentProperties properties;

    @Autowired
    ChatAttachmentRepository attachments;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    ConversationWriter conversationWriter;

    private Path root;
    private Long mine;
    private Long theirs;

    @BeforeEach
    void setUp() throws IOException {
        attachments.deleteAll();
        root = Path.of(properties.root()).toAbsolutePath();
        deleteTree(root);
        mine = conversations
                .save(Conversation.startedBy(OWNER.id(), "내 대화", null, Instant.now()))
                .id();
        theirs = conversations
                .save(Conversation.startedBy(STRANGER.id(), "남의 대화", null, Instant.now()))
                .id();
    }

    @Test
    @DisplayName("MPO 업로드는 첫 JPEG를 저장하고 저장 크기를 행에 기록한다")
    void storesFirstMpoImageAndActualByteSize() throws IOException {
        byte[] mpo;
        byte[] expected;
        try (InputStream input = getClass().getResourceAsStream("/attachments/synthetic.mpo")) {
            mpo = input.readAllBytes();
        }
        try (InputStream input = getClass().getResourceAsStream("/attachments/first.jpg")) {
            expected = input.readAllBytes();
        }
        ChatAttachment saved = upload(OWNER, mine, "image/jpeg", mpo);
        assertThat(Files.readAllBytes(fileOf(saved))).isEqualTo(expected);
        assertThat(attachments.findById(saved.id()).orElseThrow().byteSize()).isEqualTo(expected.length);
        assertThat(service.read(OWNER, mine, saved.id()).byteSize()).isEqualTo(expected.length);
    }

    @Test
    @DisplayName("깨진 JPEG도 업로드를 막지 않고 원본을 저장한다")
    void storesMalformedJpegUnchanged() throws IOException {
        ChatAttachment saved = upload(OWNER, mine, "image/jpeg", IMAGE);
        assertThat(Files.readAllBytes(fileOf(saved))).isEqualTo(IMAGE);
        assertThat(saved.byteSize()).isEqualTo(IMAGE.length);
    }

    @Test
    @DisplayName("자기 대화에 올리면 행이 생기고 파일이 그 자리에 있다")
    void uploadToOwnConversationCreatesRowAndFileInPlace() throws IOException {
        ChatAttachment saved = upload(OWNER, mine, "image/png", IMAGE);

        ChatAttachment row = attachments.findById(saved.id()).orElseThrow();
        assertThat(row.storedName()).isEqualTo(saved.id() + ".png");
        assertThat(row.messageId()).isNull();
        assertThat(row.isVisible()).isTrue();
        Path file = fileOf(saved);
        assertThat(file).exists();
        assertThat(Files.readAllBytes(file)).isEqualTo(IMAGE);
        try (Stream<Path> children = Files.list(root)) {
            assertThat(children.map(path -> path.getFileName().toString())).containsExactly("users");
        }
    }

    @Test
    @DisplayName("남의 대화에 올리면 없는 대화로 거절하고 파일을 만들지 않는다")
    void uploadToOthersConversationIsRejectedAsMissingWithoutFile() {
        assertCode(() -> upload(OWNER, theirs, "image/png", IMAGE), ErrorCode.CONVERSATION_NOT_FOUND);

        assertThat(attachments.findByConversationIdOrderByIdAsc(theirs)).isEmpty();
        assertThat(root.resolve("users")
                        .resolve(AttachmentStore.userDirectoryKey(OWNER.id()))
                        .resolve(String.valueOf(theirs)))
                .doesNotExist();
    }

    @Test
    @DisplayName("지운 대화의 첨부는 올리기 읽기 지우기 모두 404이다")
    void attachmentOfDeletedConversationIs404ForUploadReadAndDelete() {
        ChatAttachment saved = upload(OWNER, mine, "image/png", IMAGE);
        conversationWriter.deleteIfActive(mine, OWNER.id(), Instant.now());

        assertCode(() -> upload(OWNER, mine, "image/png", IMAGE), ErrorCode.CONVERSATION_NOT_FOUND);
        assertCode(() -> service.read(OWNER, mine, saved.id()), ErrorCode.CONVERSATION_NOT_FOUND);
        assertCode(() -> service.deleteByUser(OWNER, mine, saved.id()), ErrorCode.CONVERSATION_NOT_FOUND);
        assertThat(ErrorCode.CONVERSATION_NOT_FOUND.status().value()).isEqualTo(404);
        assertThat(attachments.findById(saved.id()).orElseThrow().isVisible()).isTrue();
    }

    @Test
    @DisplayName("받지 않는 형식은 거절하고 행을 만들지 않는다")
    void rejectsUnacceptedFormatWithoutCreatingRow() {
        assertCode(() -> upload(OWNER, mine, "application/pdf", IMAGE), ErrorCode.VALIDATION_FAILED);

        assertThat(attachments.findByConversationIdOrderByIdAsc(mine)).isEmpty();
    }

    @Test
    @DisplayName("한 장 상한을 넘으면 거절하고 행을 만들지 않는다")
    void rejectsOverPerImageLimitWithoutCreatingRow() {
        byte[] tooLarge = new byte[(int) (properties.maxBytes() + 1)];

        assertCode(() -> upload(OWNER, mine, "image/jpeg", tooLarge), ErrorCode.VALIDATION_FAILED);

        assertThat(attachments.findByConversationIdOrderByIdAsc(mine)).isEmpty();
    }

    @Test
    @DisplayName("설정을 생략하면 사진 서른 장을 한 번에 보낼 수 있다")
    void defaultsToThirtyImages() {
        AttachmentProperties defaults =
                new AttachmentProperties("/attachments", "/agent-attachments", null, null, null);

        assertThat(defaults.maxFiles()).isEqualTo(30);
    }

    @Test
    @DisplayName("묶이지 않은 사진 서른 장은 올리고 서른한 번째는 거절한다")
    void acceptsThirtyUnboundImagesAndRejectsTheThirtyFirst() {
        assertThat(properties.maxFiles()).isEqualTo(30);
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            ids.add(upload(OWNER, mine, "image/png", IMAGE).id());
        }

        service.requireAttachable(mine, ids);
        assertCode(() -> upload(OWNER, mine, "image/png", IMAGE), ErrorCode.VALIDATION_FAILED);

        List<Long> tooMany = new ArrayList<>(ids);
        tooMany.add(Long.MAX_VALUE);
        assertCode(() -> service.requireAttachable(mine, tooMany), ErrorCode.VALIDATION_FAILED);

        assertThat(attachments.findByConversationIdOrderByIdAsc(mine)).hasSize(30);
    }

    @Test
    @DisplayName("이미 보낸 사진은 세지 않아 새로 올릴 수 있다")
    void alreadySentImagesDoNotCountSoNewOnesCanBeUploaded() {
        List<Long> sent = new ArrayList<>();
        for (int i = 0; i < properties.maxFiles(); i++) {
            sent.add(upload(OWNER, mine, "image/png", IMAGE).id());
        }
        service.attach(501L, mine, sent);

        ChatAttachment next = upload(OWNER, mine, "image/png", IMAGE);

        assertThat(attachments.findById(next.id())).isPresent();
    }

    @Test
    @DisplayName("지운 첨부를 읽으면 있었다는 것만 알린다")
    void readingDeletedAttachmentOnlyReportsItExisted() {
        ChatAttachment saved = upload(OWNER, mine, "image/png", IMAGE);
        service.deleteByUser(OWNER, mine, saved.id());

        assertCode(() -> service.read(OWNER, mine, saved.id()), ErrorCode.ATTACHMENT_GONE);
    }

    @Test
    @DisplayName("행은 보이는데 파일이 없으면 지워진 첨부로 알린다")
    void reportsAttachmentAsRemovedWhenRowVisibleButFileMissing() throws IOException {
        ChatAttachment saved = upload(OWNER, mine, "image/png", IMAGE);
        // 정리 작업이 파일을 지우고 지운 시각을 아직 적지 않은 순간이다.
        Files.delete(fileOf(saved));
        assertCode(() -> service.read(OWNER, mine, saved.id()), ErrorCode.ATTACHMENT_GONE);
    }

    @Test
    @DisplayName("제어 문자만으로 된 이름은 대체 이름이 된다")
    void nameOfOnlyControlCharsBecomesFallbackName() {
        ChatAttachment saved = service.upload(
                OWNER, mine, "\n\r\t\u0000", "image/png", IMAGE.length, () -> new ByteArrayInputStream(IMAGE));

        assertThat(attachments.findById(saved.id()).orElseThrow().originalName())
                .isEqualTo("image");
    }

    @Test
    @DisplayName("없는 번호를 읽으면 없는 대화와 같은 응답이다")
    void readingMissingIdRespondsLikeMissingConversation() {
        assertCode(() -> service.read(OWNER, mine, 987654321L), ErrorCode.CONVERSATION_NOT_FOUND);
    }

    @Test
    @DisplayName("내 대화 번호에 남의 첨부 번호를 붙여도 읽지 못한다")
    void cannotReadOthersAttachmentIdUnderOwnConversationId() throws IOException {
        ChatAttachment own = upload(OWNER, mine, "image/png", IMAGE);
        ChatAttachment others = upload(STRANGER, theirs, "image/png", IMAGE);

        assertThat(fileOf(own)).exists();
        assertThat(fileOf(others)).exists();
        try (InputStream mineBody =
                        service.read(OWNER, own.conversationId(), own.id()).body();
                InputStream otherBody =
                        service.read(STRANGER, theirs, others.id()).body()) {
            assertThat(mineBody.readAllBytes()).isEqualTo(IMAGE);
            assertThat(otherBody.readAllBytes()).isEqualTo(IMAGE);
        }
        assertCode(() -> service.read(OWNER, own.conversationId(), others.id()), ErrorCode.CONVERSATION_NOT_FOUND);
        assertCode(
                () -> service.deleteByUser(OWNER, own.conversationId(), others.id()), ErrorCode.CONVERSATION_NOT_FOUND);
        assertThat(attachments.findById(others.id()).orElseThrow().isVisible()).isTrue();
    }

    @Test
    @DisplayName("자기 첨부는 올린 본문을 그대로 읽는다")
    void readsOwnAttachmentAsUploaded() throws IOException {
        ChatAttachment saved = upload(OWNER, mine, "image/webp", IMAGE);

        AttachmentContent content = service.read(OWNER, mine, saved.id());

        assertThat(content.contentType()).isEqualTo("image/webp");
        try (InputStream body = content.body()) {
            assertThat(body.readAllBytes()).isEqualTo(IMAGE);
        }
    }

    @Test
    @DisplayName("사용자가 지우면 파일이 사라지고 행은 남는다")
    void userDeleteRemovesFileAndKeepsRow() {
        ChatAttachment saved = upload(OWNER, mine, "image/gif", IMAGE);
        Path file = fileOf(saved);

        service.deleteByUser(OWNER, mine, saved.id());
        service.deleteByUser(OWNER, mine, saved.id());

        assertThat(file).doesNotExist();
        ChatAttachment row = attachments.findById(saved.id()).orElseThrow();
        assertThat(row.deletedAt()).isNotNull();
        assertThat(service.allOf(mine)).extracting(ChatAttachment::id).containsExactly(saved.id());
    }

    @Test
    @DisplayName("파일을 쓰지 못하면 행을 남기지 않는다")
    void leavesNoRowWhenFileCannotBeWritten() throws IOException {
        // 대화 디렉터리 자리에 일반 파일을 두어 디렉터리를 만들지 못하게 한다.
        Path ownerDirectory = root.resolve("users").resolve(AttachmentStore.userDirectoryKey(OWNER.id()));
        Files.createDirectories(ownerDirectory);
        Files.write(ownerDirectory.resolve(String.valueOf(mine)), new byte[] {1});

        assertThatThrownBy(() -> upload(OWNER, mine, "image/png", IMAGE)).isInstanceOf(ApiException.class);

        assertThat(attachments.findByConversationIdOrderByIdAsc(mine)).isEmpty();
    }

    @Test
    @DisplayName("묶을 수 없는 첨부는 까닭을 가르지 않고 거절한다")
    void rejectsUnbindableAttachmentWithoutRevealingReason() {
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
    @DisplayName("이미 묶인 첨부를 다시 묶으면 거절하고 처음 메시지를 가리킨다")
    void rebindingBoundAttachmentIsRejectedAndPointsToFirstMessage() {
        Long id = upload(OWNER, mine, "image/png", IMAGE).id();
        service.attach(701L, mine, List.of(id));

        assertCode(() -> service.attach(702L, mine, List.of(id)), ErrorCode.VALIDATION_FAILED);

        assertThat(attachments.findById(id).orElseThrow().messageId()).isEqualTo(701L);
    }

    @Test
    @DisplayName("요청한 첨부 순서가 저장한 position 과 조회 순서가 된다")
    void storesAndReadsAttachmentsInRequestedOrder() {
        ChatAttachment first = upload(OWNER, mine, "image/png", IMAGE);
        ChatAttachment second = upload(OWNER, mine, "image/png", IMAGE);
        ChatAttachment third = upload(OWNER, mine, "image/png", IMAGE);

        service.attach(701L, mine, List.of(third.id(), first.id(), second.id()));

        assertThat(service.allOf(mine))
                .extracting(ChatAttachment::id, ChatAttachment::position)
                .containsExactly(tuple(third.id(), 0), tuple(first.id(), 1), tuple(second.id(), 2));
    }

    @Test
    @DisplayName("뒤 첨부의 조건부 갱신이 실패하면 앞 첨부의 message id 와 position 도 남지 않는다")
    void rollsBackEarlierAttachmentWhenLaterConditionalUpdateFails() {
        ChatAttachment first = upload(OWNER, mine, "image/png", IMAGE);
        ChatAttachment alreadyBound = upload(OWNER, mine, "image/png", IMAGE);
        service.attach(701L, mine, List.of(alreadyBound.id()));

        assertCode(
                () -> service.attach(702L, mine, List.of(first.id(), alreadyBound.id())), ErrorCode.VALIDATION_FAILED);

        ChatAttachment reloadedFirst = attachments.findById(first.id()).orElseThrow();
        assertThat(reloadedFirst.messageId()).isNull();
        assertThat(reloadedFirst.position()).isZero();
        assertThat(attachments.findById(alreadyBound.id()).orElseThrow().messageId())
                .isEqualTo(701L);
    }

    private ChatAttachment upload(CurrentUser user, Long conversationId, String contentType, byte[] body) {
        return service.upload(
                user, conversationId, "photo", contentType, body.length, () -> new ByteArrayInputStream(body));
    }

    private Path fileOf(ChatAttachment attachment) {
        return root.resolve("users")
                .resolve(AttachmentStore.userDirectoryKey(attachment.uploadedByUserId()))
                .resolve(attachment.conversationId().toString())
                .resolve(attachment.storedName());
    }

    private static void assertCode(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.code()).isEqualTo(expected));
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
