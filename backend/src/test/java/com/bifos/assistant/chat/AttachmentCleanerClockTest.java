package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.chat.application.AttachmentCleaner;
import com.bifos.assistant.chat.domain.ChatAttachment;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.AttachmentProperties;
import com.bifos.assistant.chat.infra.AttachmentStore;
import com.bifos.assistant.chat.infra.ChatAttachmentRepository;
import com.bifos.assistant.chat.infra.ConversationRepository;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** 일정이 부르는 {@code runScheduled} 가 주입받은 시계의 시각을 기준으로 첨부를 지우는지 확인한다. */
@SpringBootTest
@ActiveProfiles("test")
class AttachmentCleanerClockTest {

    private static final Instant NOW = Instant.parse("2026-03-01T04:00:00Z");

    @Autowired
    AttachmentStore store;

    @Autowired
    AttachmentProperties properties;

    @Autowired
    ChatAttachmentRepository attachments;

    @Autowired
    ConversationRepository conversations;

    private Long conversationId;

    @BeforeEach
    void setUp() throws IOException {
        attachments.deleteAll();
        deleteTree(Path.of(properties.root()).toAbsolutePath());
        conversationId = conversations
                .save(Conversation.startedBy(9202L, "시계 대화", null, Instant.now()))
                .id();
    }

    @Test
    @DisplayName("고정한 시각보다 앞서 만료된 첨부만 지우고 그 뒤에 만료되는 첨부는 남긴다")
    void runScheduledDeletesOnlyAttachmentsExpiredBeforeClockInstant() {
        ChatAttachment expired = stored(NOW.minus(Duration.ofDays(1)));
        ChatAttachment live = stored(NOW.plus(Duration.ofDays(1)));
        AttachmentCleaner cleaner = new AttachmentCleaner(attachments, store, Clock.fixed(NOW, ZoneOffset.UTC));

        cleaner.runScheduled();

        assertThat(attachments.findById(expired.id()).orElseThrow().deletedAt()).isEqualTo(NOW);
        assertThat(attachments.findById(live.id()).orElseThrow().deletedAt()).isNull();
    }

    private ChatAttachment stored(Instant expiresAt) {
        ChatAttachment attachment = attachments.save(
                ChatAttachment.of(conversationId, 9202L, "photo.png", "image/png", 3, expiresAt, Instant.now()));
        attachment.nameStoredFile(AttachmentStore.storedName(attachment.id(), "png"));
        attachments.save(attachment);
        store.save(attachment, new ByteArrayInputStream(new byte[] {1, 2, 3}));
        return attachment;
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path each : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(each);
            }
        }
    }
}
