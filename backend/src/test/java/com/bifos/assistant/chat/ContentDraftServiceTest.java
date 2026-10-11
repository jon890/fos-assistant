package com.bifos.assistant.chat;

import tools.jackson.databind.node.ObjectNode;
import com.bifos.assistant.chat.application.ConversationPurger;
import org.springframework.dao.DataAccessResourceFailureException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.chat.application.AttachmentCleaner;
import com.bifos.assistant.chat.application.ContentDraftCleaner;
import com.bifos.assistant.chat.domain.ContentDraftException;
import com.bifos.assistant.chat.application.ContentDraftInputReader;
import com.bifos.assistant.chat.application.ContentDraftReferences;
import com.bifos.assistant.chat.application.ContentDraftService;
import com.bifos.assistant.chat.application.model.ContentDraftInput;
import com.bifos.assistant.chat.domain.ChatAttachment;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.domain.type.ContentDraftRevisionStatus;
import com.bifos.assistant.chat.infra.AttachmentStore;
import com.bifos.assistant.chat.infra.ChatMessageRepository;
import com.bifos.assistant.chat.infra.ContentDraftAssetRepository;
import com.bifos.assistant.chat.infra.ContentDraftBodies;
import com.bifos.assistant.chat.infra.ContentDraftRepository;
import com.bifos.assistant.chat.infra.ContentDraftRevisionRepository;
import com.bifos.assistant.crypto.domain.SealedText;
import com.bifos.assistant.crypto.domain.TextCipher;
import com.bifos.assistant.shared.error.ErrorCode;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ContentDraftServiceTest extends ContentDraftFixture {
    @Test
    @DisplayName("생성 수정 이전 판과 중복 이미지 위치를 암호화하여 보존한다")
    void preservesEncryptedRevisionsAndDuplicateImagePositions() {
        var initial = drafts.create(owner, conversation.id(), inputDraft("첫 원고", true));
        UUID id = initial.draft().draftId();
        for (int revision = 1; revision < 4; revision++) {
            assertThat(drafts.revise(owner, conversation.id(), id, revision, inputDraft("원고" + revision, false)).draft().revision())
                .isEqualTo(revision + 1);
        }
        assertThat(drafts.read(owner, conversation.id(), id, 1L).draft().title()).isEqualTo("첫 원고");
        assertThat(assetRows.findAll()).extracting(a -> a.position()).containsExactly(0, 1);
        assertThat(revisionRows.findAll()).allSatisfy(row -> {
            assertThat(row.body()).startsWith("v1.").doesNotContain("원고");
            assertThat(row.bodyKeyId()).isNotNull();
        });
        assertThat(drafts.list(owner, conversation.id()).getFirst().status()).isEqualTo("DRAFTING");
    }


    @Test
    @DisplayName("실제 트랜잭션의 동시 CAS는 한 판만 수락한다")
    void acceptsExactlyOneConcurrentCas() throws Exception {
        var id = drafts.create(owner, conversation.id(), inputDraft("첫", false)).draft().draftId();
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var pending = List.of("수정 하나", "수정 둘").stream().map(title -> pool.submit(() -> {
                ready.countDown();
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                try {
                    drafts.revise(owner, conversation.id(), id, 1, inputDraft(title, false));
                    return "accepted";
                } catch (ContentDraftException ex) {
                    assertThat(ex.code()).isEqualTo(ErrorCode.CONTENT_DRAFT_CONFLICT);
                    assertThat(ex.currentRevision()).isEqualTo(2);
                    return "conflict";
                }
            })).toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(pending.get(0).get(10, TimeUnit.SECONDS), pending.get(1).get(10, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder("accepted", "conflict");
        }
        assertThat(revisionRows.count()).isEqualTo(2);
    }


    @Test
    @DisplayName("쓰기 IO와 본문 key 장애는 기존 암호문과 번호를 보존한다")
    void preservesBodyKeysAndRevisionOnTransientIoAndCryptoFailure() {
        var id = drafts.create(owner, conversation.id(), inputDraft("비밀", true)).draft().draftId();
        var previous = revisionRows.findAll().getFirst();
        var failingStore = new AttachmentStore(properties) {
            @Override
            public InputStream open(ChatAttachment row) {
                throw new UncheckedIOException(new IOException("synthetic"));
            }
        };
        var refs = new ContentDraftReferences(attachments, failingStore, observations, bodies, clock, json);
        code(() -> localDrafts(refs, draftBodies).revise(owner, conversation.id(), id, 1, inputDraft("새", true)),
            ErrorCode.CONTENT_DRAFT_UNAVAILABLE);
        assertThat(localDrafts(refs, draftBodies).read(owner, conversation.id(), id, null).draft().title()).isEqualTo("비밀");
        TextCipher failed = new TextCipher() {
            @Override
            public boolean enabled() {
                return true;
            }


            @Override
            public Optional<SealedText> seal(Long user, String aad, String plain) {
                throw new IllegalStateException("synthetic");
            }


            @Override
            public Optional<String> open(Long key, Long user, String aad, String value) {
                return Optional.empty();
            }
        };
        var unavailable = localDrafts(refs, new ContentDraftBodies(failed));
        code(() -> unavailable.read(owner, conversation.id(), id, null), ErrorCode.CONTENT_DRAFT_UNAVAILABLE);
        code(() -> unavailable.revise(owner, conversation.id(), id, 1, inputDraft("새", false)), ErrorCode.CONTENT_DRAFT_UNAVAILABLE);
        code(() -> unavailable.create(owner, conversation.id(), inputDraft("새", false)), ErrorCode.CONTENT_DRAFT_UNAVAILABLE);
        assertThat(draftRows.count()).isEqualTo(1);
        assertThat(draftRows.findAll().getFirst().currentRevision()).isEqualTo(1);
        var stored = revisionRows.findById(previous.id()).orElseThrow();
        assertThat(stored.body()).isEqualTo(previous.body());
        assertThat(stored.bodyKeyId()).isEqualTo(previous.bodyKeyId());
        assertThat(stored.status()).isEqualTo(ContentDraftRevisionStatus.VALID);
    }


    @Test
    @DisplayName("삭제 사전 커밋은 파일 실패와 재시작 뒤에도 모든 이전 본문을 차단한다")
    void keepsDeletionBarrierAcrossFileFailureAndRestart() {
        var id = drafts.create(owner, conversation.id(), inputDraft("첫", true)).draft().draftId();
        drafts.revise(owner, conversation.id(), id, 1, inputDraft("둘", true));
        var failingStore = new AttachmentStore(properties) {
            @Override
            public void delete(ChatAttachment row) {
                throw new IllegalStateException("synthetic");
            }
        };
        var interrupted = new AttachmentCleaner(attachments, failingStore, clock, mutations, observationCleaner, cleaner);
        assertThatThrownBy(() -> interrupted.delete(photo, NOW)).isInstanceOf(IllegalStateException.class);
        assertThat(attachments.findById(photo.id()).orElseThrow().deletionRequestedAt()).isEqualTo(NOW);
        assertErased(ContentDraftRevisionStatus.STALE);
        code(() -> drafts.read(owner, conversation.id(), id, 1L), ErrorCode.CONTENT_DRAFT_STALE);
        code(() -> drafts.read(owner, conversation.id(), id, null), ErrorCode.CONTENT_DRAFT_STALE);
        code(() -> drafts.create(owner, conversation.id(), inputDraft("새", true)), ErrorCode.ATTACHMENT_GONE);
        new AttachmentCleaner(attachments, store, clock, mutations, observationCleaner, cleaner).cleanExpired(NOW);
        assertErased(ContentDraftRevisionStatus.STALE);
        assertThat(drafts.revise(owner, conversation.id(), id, 2, inputDraft("사진 제거", false)).draft().revision()).isEqualTo(3);
    }


    @Test
    @DisplayName("STALE도 보관 만료 뒤 EXPIRED가 되고 본문은 복구되지 않는다")
    void expiresStaleAndValidRevisionsAtRetentionBoundary() {
        var id = drafts.create(owner, conversation.id(), inputDraft("첫", true)).draft().draftId();
        attachmentCleaner.delete(photo, NOW);
        clock.set(draftRows.findAll().getFirst().expiresAt());
        assertThat(cleaner.cleanExpired(clock.instant())).isEqualTo(1);
        assertErased(ContentDraftRevisionStatus.EXPIRED);
        code(() -> drafts.read(owner, conversation.id(), id, null), ErrorCode.CONTENT_DRAFT_GONE);
        code(() -> drafts.revise(owner, conversation.id(), id, 1, inputDraft("새", false)), ErrorCode.CONTENT_DRAFT_GONE);
        assertThat(drafts.list(owner, conversation.id()).getFirst().title()).isNull();
        assertThat(drafts.list(owner, conversation.id()).getFirst().status()).isEqualTo("EXPIRED");
    }


    @Test
    @DisplayName("타첨부 관찰과 지문 변경은 STALE이며 일시 복호화 장애는 보존한다")
    void distinguishesObservationMismatchFromTransientDecryption() throws IOException {
        record(0, UUID.randomUUID(), input(), model());
        var observation = observations.findAll().getFirst();
        var node = json.valueToTree(inputDraft("관찰 참조", true));
        var image = (ObjectNode)node.get("blocks").get(0);
        image.set("observationRef", json.createObjectNode().put("id", observation.id().toString()).put("revision", 1));
        var accepted = reader.read(node);
        var id = drafts.create(owner, conversation.id(), accepted).draft().draftId();
        jdbc.update("update media_observation set body_key_id=? where id=?", Long.MAX_VALUE, observation.id());
        code(() -> drafts.read(owner, conversation.id(), id, null), ErrorCode.CONTENT_DRAFT_UNAVAILABLE);
        assertThat(revisionRows.findAll().getFirst().body()).isNotNull();
        jdbc.update("update media_observation set body_key_id=? where id=?", observation.bodyKeyId(), observation.id());
        byte[] changed = IMAGE.clone();
        changed[changed.length - 1]++;
        Files.write(file(photo), changed);
        code(() -> drafts.revise(owner, conversation.id(), id, 1, accepted), ErrorCode.CONTENT_DRAFT_STALE);
        observations.deleteAll();
        code(() -> drafts.read(owner, conversation.id(), id, null), ErrorCode.CONTENT_DRAFT_STALE);
        assertErased(ContentDraftRevisionStatus.STALE);
    }


    @Test
    @DisplayName("살펴보기와 메시지 없는 자동 대화는 원고 없이 기존 삭제 경로를 유지한다")
    void blocksCheckAndEmptyTaskConversationsWithoutLeavingForeignKeys() {
        var check = conversations.save(Conversation.startedForCheck(owner.id(), "살펴보기", null, NOW));
        var task = conversations.save(Conversation.startedForTask(owner.id(), "자동", null, null, NOW));
        jdbc.update("update conversation set task_id=7 where id=?", task.id());
        code(() -> drafts.create(owner, check.id(), inputDraft("새", false)), ErrorCode.CONVERSATION_NOT_FOUND);
        code(() -> drafts.create(owner, task.id(), inputDraft("새", false)), ErrorCode.CONVERSATION_NOT_FOUND);
        conversations.deleteById(task.id());
        assertThat(draftRows.count()).isZero();
    }


    @Test
    @DisplayName("파일 삭제를 멈춘 동안 새 쓰기와 이전 판이 모두 삭제 장벽을 본다")
    void blocksWritesAndHistoryWhileFileDeletionIsPaused() throws Exception {
        var id = drafts.create(owner, conversation.id(), inputDraft("보존", true)).draft().draftId();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var paused = new AttachmentStore(properties) {
            @Override
            public void delete(ChatAttachment row) {
                entered.countDown();
                try {
                    assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException ex) {
                    throw new IllegalStateException(ex);
                }
                super.delete(row);
            }
        };
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var deletion = pool.submit(() -> new AttachmentCleaner(attachments, paused, clock, mutations, observationCleaner, cleaner).delete(photo, NOW));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                code(() -> drafts.create(owner, conversation.id(), inputDraft("새", true)), ErrorCode.ATTACHMENT_GONE);
                code(() -> drafts.revise(owner, conversation.id(), id, 1, inputDraft("새", true)), ErrorCode.ATTACHMENT_GONE);
                code(() -> drafts.read(owner, conversation.id(), id, 1L), ErrorCode.CONTENT_DRAFT_STALE);
                assertErased(ContentDraftRevisionStatus.STALE);
            } finally {
                release.countDown();
            }
            assertThat(deletion.get(10, TimeUnit.SECONDS)).isTrue();
        }
    }


    @Test
    @DisplayName("업로더 변경과 대화 purge는 잠금 안에서 본문과 자식을 정리한다")
    void cleansOwnerMismatchAndPurgesDraftChildrenBeforeAttachments() {
        drafts.create(owner, conversation.id(), inputDraft("본문", true));
        var other = user();
        jdbc.update("update chat_attachment set uploaded_by_user_id=? where id=?", other.id(), photo.id());
        assertThat(cleaner.cleanExpired(NOW)).isEqualTo(1);
        assertErased(ContentDraftRevisionStatus.STALE);
        jdbc.update("update chat_attachment set uploaded_by_user_id=? where id=?", owner.id(), photo.id());
        jdbc.update("update conversation set deleted_at=? where id=?", NOW, conversation.id());
        assertThat(purger.purgeDue(NOW)).isEqualTo(1);
        assertThat(assetRows.count()).isZero();
        assertThat(revisionRows.count()).isZero();
        assertThat(draftRows.count()).isZero();
        assertThat(attachments.findById(photo.id())).isEmpty();
    }


    @Test
    @DisplayName("저장 SQL 실패는 CAS 번호와 새 revision을 함께 되돌린다")
    void rollsBackCasAndBodyOnSqlFailure() {
        var id = drafts.create(owner, conversation.id(), inputDraft("첫", false)).draft().draftId();
        var broken = fail(ContentDraftRevisionRepository.class, revisionRows, "saveAndFlush", 1);
        var failing = new ContentDraftService(access, messages, mutations, draftRows, broken, assetRows,
            new ContentDraftReferences(attachments, store, observations, bodies, clock, json), draftBodies, reader, json, clock);
        assertThatThrownBy(() -> failing.revise(owner, conversation.id(), id, 1, inputDraft("새", false)))
            .isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(draftRows.findAll().getFirst().currentRevision()).isEqualTo(1);
        assertThat(revisionRows.count()).isEqualTo(1);
    }


    @Test
    @DisplayName("같은 사용자라도 다른 원고·revision의 암호문은 AAD가 달라 열리지 않는다")
    void rejectsCiphertextMovedAcrossDraftsAndRevisions() {
        var first = drafts.create(owner, conversation.id(), inputDraft("첫", false)).draft().draftId();
        var second = drafts.create(owner, conversation.id(), inputDraft("둘", false)).draft().draftId();
        var rows = revisionRows.findAll();
        jdbc.update("update content_draft_revision set body=?,body_key_id=? where id=?", rows.get(0).body(), rows.get(0).bodyKeyId(), rows.get(1).id());
        code(() -> drafts.read(owner, conversation.id(), second, null), ErrorCode.CONTENT_DRAFT_UNAVAILABLE);
        assertThat(revisionRows.findById(rows.get(1).id()).orElseThrow().status()).isEqualTo(ContentDraftRevisionStatus.VALID);
        drafts.revise(owner, conversation.id(), first, 1, inputDraft("셋", false));
        var current = revisionRows.findAll().getLast();
        jdbc.update("update content_draft_revision set body=?,body_key_id=? where id=?", rows.get(0).body(), rows.get(0).bodyKeyId(), current.id());
        code(() -> drafts.read(owner, conversation.id(), first, null), ErrorCode.CONTENT_DRAFT_UNAVAILABLE);
        assertThat(drafts.read(owner, conversation.id(), first, 1L).draft().title()).isEqualTo("첫");
    }


    @Test
    @DisplayName("없는 타대화 미전송 첨부는 같은 410으로 응답한다")
    void rejectsUnavailableAssetsWithTheSameGoneCode() {
        var other = user();
        var another = conversations.save(Conversation.startedBy(other.id(), "타 대화", null, NOW));
        var foreign = photo(other, another);
        var pending = attachmentService.upload(owner, conversation.id(), "a.gif", "image/gif", IMAGE.length,
            () -> new ByteArrayInputStream(IMAGE));
        for (Long id : List.of(Long.MAX_VALUE, foreign.id(), pending.id())) {
            var node = json.valueToTree(inputDraft("본문", true));
            ((ObjectNode)node.get("blocks").get(0)).put("assetId", id.toString());
            code(() -> drafts.create(owner, conversation.id(), reader.read(node)), ErrorCode.ATTACHMENT_GONE);
        }
        assertThat(draftRows.count()).isZero();
    }

}

/** REST와 실제 MySQL이 같은 서비스·합성 첨부 fixture를 쓴다. */
abstract class ContentDraftFixture extends ObservationFixture {
    @Autowired
    ContentDraftService drafts;
    @Autowired
    ContentDraftRepository draftRows;
    @Autowired
    ContentDraftRevisionRepository revisionRows;
    @Autowired
    ContentDraftAssetRepository assetRows;
    @Autowired
    ContentDraftCleaner cleaner;
    @Autowired
    ContentDraftBodies draftBodies;
    @Autowired
    ContentDraftInputReader reader;
    @Autowired
    ChatMessageRepository messages;
    @Autowired
    ConversationPurger purger;


    @AfterEach
    void removeDrafts() {
        assetRows.deleteAll();
        revisionRows.deleteAll();
        draftRows.deleteAll();
    }


    ContentDraftService localDrafts(ContentDraftReferences refs, ContentDraftBodies localBodies) {
        return new ContentDraftService(access, messages, mutations, draftRows, revisionRows, assetRows, refs, localBodies, reader, json, clock);
    }


    ContentDraftInput inputDraft(String title, boolean image) {
        var node = json.createObjectNode().put("title", title).put("category", "분류");
        node.set("tags", json.createArrayNode());
        var blocks = json.createArrayNode();
        if (image) {
            blocks.add(json.createObjectNode().put("type", "image").put("assetId", photo.id().toString()));
            blocks.add(json.createObjectNode().put("type", "image").put("assetId", photo.id().toString()));
        }
        node.set("blocks", blocks);
        return reader.read(node);
    }


    void assertErased(ContentDraftRevisionStatus status) {
        assertThat(revisionRows.findAll()).isNotEmpty().allSatisfy(row -> {
            assertThat(row.status()).isEqualTo(status);
            assertThat(row.body()).isNull();
            assertThat(row.bodyKeyId()).isNull();
        });
    }
}
