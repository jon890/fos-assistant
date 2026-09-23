package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.chat.application.AttachmentCleaner;
import com.bifos.assistant.chat.application.AttachmentProperties;
import com.bifos.assistant.chat.application.AttachmentService;
import com.bifos.assistant.chat.domain.ChatAttachment;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.AttachmentStore;
import com.bifos.assistant.chat.infra.ChatAttachmentRepository;
import com.bifos.assistant.chat.infra.ConversationRepository;
import java.io.ByteArrayInputStream;
import java.io.IOException;
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
import org.springframework.test.context.ActiveProfiles;

/** 보관 기간이 지난 첨부의 파일만 지우고 행은 남기는지 확인한다. 일정을 기다리지 않고 직접 부른다. */
@SpringBootTest
@ActiveProfiles("test")
class AttachmentCleanerTest {

    private static final Instant NOW = Instant.parse("2026-03-01T04:00:00Z");
    private static final Instant EXPIRED = NOW.minus(Duration.ofDays(1));
    private static final Instant LIVE = NOW.plus(Duration.ofDays(1));

    @Autowired AttachmentCleaner cleaner;
    @Autowired AttachmentService service;
    @Autowired AttachmentStore store;
    @Autowired AttachmentProperties properties;
    @Autowired ChatAttachmentRepository attachments;
    @Autowired ConversationRepository conversations;

    private Path root;
    private Long conversationId;

    @BeforeEach
    void 준비한다() throws IOException {
        attachments.deleteAll();
        root = Path.of(properties.root()).toAbsolutePath();
        deleteTree(root);
        conversationId = conversations.save(Conversation.startedBy(9201L, "정리 대화", null)).id();
    }

    @Test
    void 기간이_지난_것만_파일을_지우고_행에_지운_시각을_적는다() {
        ChatAttachment expired = stored(EXPIRED);
        ChatAttachment live = stored(LIVE);

        int deleted = cleaner.cleanExpired(NOW);

        assertThat(deleted).isEqualTo(1);
        assertThat(fileOf(expired)).doesNotExist();
        assertThat(reload(expired).deletedAt()).isEqualTo(NOW);
        assertThat(fileOf(live)).exists();
        assertThat(reload(live).deletedAt()).isNull();
    }

    @Test
    void 이미_지운_것은_다시_지우지_않는다() {
        ChatAttachment gone = stored(EXPIRED);
        Instant firstDeletion = NOW.minus(Duration.ofHours(3));
        store.delete(gone);
        gone.markDeleted(firstDeletion);
        attachments.save(gone);

        int deleted = cleaner.cleanExpired(NOW);

        assertThat(deleted).isZero();
        assertThat(reload(gone).deletedAt()).isEqualTo(firstDeletion);
    }

    @Test
    void 메시지에_묶였든_아니든_기간이_지났으면_함께_지운다() {
        ChatAttachment bound = stored(EXPIRED);
        ChatAttachment unbound = stored(EXPIRED);
        service.attach(801L, conversationId, List.of(bound.id()));

        int deleted = cleaner.cleanExpired(NOW);

        assertThat(deleted).isEqualTo(2);
        assertThat(reload(bound).messageId()).isEqualTo(801L);
        assertThat(reload(bound).isVisible()).isFalse();
        assertThat(reload(unbound).messageId()).isNull();
        assertThat(reload(unbound).isVisible()).isFalse();
    }

    @Test
    void 한_건이_실패해도_나머지를_지운다() throws IOException {
        ChatAttachment broken = stored(EXPIRED);
        ChatAttachment fine = stored(EXPIRED);
        // 파일 자리를 비어 있지 않은 디렉터리로 바꿔 지우기가 실패하게 한다.
        Files.delete(fileOf(broken));
        Files.createDirectories(fileOf(broken));
        Files.write(fileOf(broken).resolve("inside"), new byte[] {1});

        int deleted = cleaner.cleanExpired(NOW);

        assertThat(deleted).isEqualTo(1);
        assertThat(reload(broken).isVisible()).isTrue();
        assertThat(fileOf(fine)).doesNotExist();
        assertThat(reload(fine).deletedAt()).isEqualTo(NOW);
    }

    private ChatAttachment stored(Instant expiresAt) {
        ChatAttachment attachment = attachments.save(
                ChatAttachment.of(conversationId, 9201L, "photo.png", "image/png", 3, expiresAt));
        attachment.nameStoredFile(AttachmentStore.storedName(attachment.id(), "png"));
        attachments.save(attachment);
        store.save(conversationId, attachment.id(), "png", new ByteArrayInputStream(new byte[] {1, 2, 3}));
        return attachment;
    }

    private ChatAttachment reload(ChatAttachment attachment) {
        return attachments.findById(attachment.id()).orElseThrow();
    }

    private Path fileOf(ChatAttachment attachment) {
        return root.resolve(String.valueOf(conversationId)).resolve(attachment.id() + ".png");
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
