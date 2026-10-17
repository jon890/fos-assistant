package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.chat.application.AttachmentCleaner;
import com.bifos.assistant.chat.application.ContentDraftCleaner;
import com.bifos.assistant.chat.application.ConversationPurger;
import com.bifos.assistant.chat.conversation.application.ConversationWriter;
import com.bifos.assistant.chat.domain.ChatAttachment;
import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.domain.type.ObservationStatus;
import com.bifos.assistant.chat.infra.AttachmentStore;
import com.bifos.assistant.shared.error.ErrorCode;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

class MediaObservationCleanupTest extends ObservationFixture {
    @Test
    @DisplayName("관찰 만료와 첨부 만료를 테스트 시계로 나누어 본문 차단과 실제 정리를 확인한다")
    void separatesObservationExpiryFromAttachmentExpiryUsingTestClock() {
        record(0, UUID.randomUUID(), input(), model());
        jdbc.update("update media_observation set expires_at=?", Timestamp.from(NOW.plusSeconds(1)));
        clock.advance(Duration.ofSeconds(2));
        var expiredObservation = service.list(owner, conversation.id(), null, 10).getFirst();
        assertThat(expiredObservation.observation()).isNull();
        assertThat(observationCleaner.cleanExpired(clock.instant())).isEqualTo(1);
        assertThat(file(photo)).exists();
        assertThat(observations.count()).isZero();
        assertThat(requests.count()).isZero();
        record(0, UUID.randomUUID(), input(), model());
        clock.set(photo.expiresAt().plusSeconds(1));
        var expiredAttachment = service.list(owner, conversation.id(), null, 10).getFirst();
        assertThat(expiredAttachment.status()).isEqualTo(ObservationStatus.UNAVAILABLE);
        assertThat(expiredAttachment.observation()).isNull();
        assertThat(expiredAttachment.sourceFingerprint()).isNull();
        assertThat(expiredAttachment.revision()).isNull();
        code(() -> record(0, UUID.randomUUID(), input(), model()), ErrorCode.ATTACHMENT_GONE);
        attachmentCleaner.runScheduled();
        assertThat(observations.count()).isZero();
        assertThat(requests.count()).isZero();
        assertThat(file(photo)).doesNotExist();
    }


    @Autowired
    ContentDraftCleaner drafts;

    @Autowired
    ConversationWriter writer;

    @Autowired
    ConversationPurger purger;


    @Test
    @DisplayName("파일 작업 실패 전 관찰과 alias 삭제를 커밋하고 시작 때 재시도한다")
    void commitsObservationAndAliasDeletionBeforeFailingFileOperationAndRetriesOnStartup() {
        record(0, UUID.randomUUID(), input(), model());
        AttachmentStore failing = new AttachmentStore(properties) {
            @Override
            public void delete(ChatAttachment row) {
                assertThat(observations.count()).isZero();
                assertThat(requests.count()).isZero();
                assertThat(attachments.findById(row.id()).orElseThrow().deletionRequestedAt())
                    .isEqualTo(NOW);
                code(() -> record(0, UUID.randomUUID(), input(), model()), ErrorCode.ATTACHMENT_GONE);
                throw new IllegalStateException("synthetic file deletion failure");
            }
        };
        var cleaner = new AttachmentCleaner(attachments, failing, clock, mutations, observationCleaner, drafts);
        assertThatThrownBy(() -> cleaner.delete(photo, NOW)).isInstanceOf(IllegalStateException.class);
        assertThat(observations.count()).isZero();
        assertThat(requests.count()).isZero();
        assertThat(file(photo)).exists();
        assertThat(attachments.findById(photo.id()).orElseThrow().deletedAt()).isNull();
        clock.advance(Duration.ofSeconds(1));
        attachmentCleaner.runScheduled();
        assertThat(file(photo)).doesNotExist();
        assertThat(attachments.findById(photo.id()).orElseThrow().deletedAt()).isEqualTo(clock.instant());
    }


    @Test
    @DisplayName("관찰만 만료되면 첨부 삭제 요청 없이 정리한다")
    void cleansObservationOnlyExpiryWithoutRequestingAttachmentDeletion() {
        record(0, UUID.randomUUID(), input(), model());
        jdbc.update("update media_observation set expires_at=?", Timestamp.from(NOW.minusSeconds(1)));
        assertThat(observationCleaner.cleanExpired(NOW)).isEqualTo(1);
        assertThat(observations.count()).isZero();
        assertThat(requests.count()).isZero();
        assertThat(attachments.findById(photo.id()).orElseThrow().deletionRequestedAt())
            .isNull();
        assertThat(attachments.findById(photo.id()).orElseThrow().deletedAt()).isNull();
        assertThat(file(photo)).exists();
    }


    @Test
    @DisplayName("소유자 변경 후보는 차단하고 다른 사용자와 관찰 소유자 불일치를 정리한다")
    void keepsOwnerTransferBlockedWhileCleaningOtherUsersAndMismatchedObservationOwner() {
        var alias = UUID.randomUUID();
        record(0, alias, input(), model());
        var other = user();
        var otherConversation = conversations.save(Conversation.startedBy(other.id(), "독립 사용자", null, NOW));
        var otherPhoto = photo(other, otherConversation);
        service.record(other, otherConversation.id(), otherPhoto.id(), 0, UUID.randomUUID(), input(), model());
        jdbc.update("update conversation set user_id=? where id=?", other.id(), conversation.id());
        jdbc.update(
            "update media_observation set expires_at=? where attachment_id=?",
            Timestamp.from(NOW.minusSeconds(1)),
            otherPhoto.id());
        assertThat(observationCleaner.cleanExpired(NOW)).isEqualTo(1);
        assertThat(observations.count()).isEqualTo(1);
        assertThat(requests.count()).isEqualTo(1);
        code(() -> record(0, alias, input(), model()), ErrorCode.CONVERSATION_NOT_FOUND);
        assertThat(service.list(other, conversation.id(), null, 10).getFirst().observation())
            .isNull();
        jdbc.update("update conversation set user_id=? where id=?", owner.id(), conversation.id());
        jdbc.update("update media_observation set owner_user_id=? where attachment_id=?", other.id(), photo.id());
        assertThat(observationCleaner.cleanExpired(NOW)).isEqualTo(1);
        assertThat(requests.count()).isZero();
    }


    @Test
    @DisplayName("대화 정리는 실행 기록을 보존하며 관찰과 alias를 함께 지운다")
    void purgesConversationAndCascadesAliasesWithoutChangingExecutionHistory() {
        // 공유 컨텍스트의 이전 검사에서 남은 정리 후보를 먼저 처리하고 이번 대화의 건수를 따로 검증한다.
        purger.purgeDue(NOW);
        record(0, UUID.randomUUID(), input(), model());
        writer.deleteIfActive(conversation.id(), owner.id(), NOW);
        assertThat(purger.purgeDue(NOW)).isEqualTo(1);
        assertThat(observations.count()).isZero();
        assertThat(requests.count()).isZero();
        assertThat(attachments.findById(photo.id())).isEmpty();
        assertThat(conversations.findById(conversation.id()).orElseThrow().purgedAt())
            .isEqualTo(NOW);
    }


    @Test
    @DisplayName("트랜잭션 안에서도 coordinator 밖 정리는 거부한다")
    void disallowsCleanerOutsideCoordinatorEvenInsideTransaction() {
        record(0, UUID.randomUUID(), input(), model());
        assertThatThrownBy(() -> observationCleaner.deleteForAttachment(conversation.id(), photo.id()))
            .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new TransactionTemplate(manager)
            .executeWithoutResult(status -> observationCleaner.deleteForConversation(conversation.id())))
                .isInstanceOf(IllegalStateException.class);
        assertThat(observations.count()).isEqualTo(1);
        assertThat(requests.count()).isEqualTo(1);
    }
}
