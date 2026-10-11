package com.bifos.assistant.chat;

import com.bifos.assistant.chat.application.ContentDraftReferences;
import com.bifos.assistant.chat.application.ContentDraftService;
import com.bifos.assistant.chat.domain.entity.ContentDraft;
import com.bifos.assistant.chat.infra.ContentDraftAssetRepository;
import com.bifos.assistant.chat.infra.ContentDraftBodies;
import com.bifos.assistant.chat.infra.ContentDraftCreationCallRepository;
import com.bifos.assistant.chat.infra.ContentDraftCreationRequestRepository;
import com.bifos.assistant.crypto.domain.SealedText;
import org.mockito.AdditionalAnswers;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.chat.application.model.ContentDraftCreationContext;
import com.bifos.assistant.chat.domain.ContentDraftException;
import com.bifos.assistant.chat.domain.type.ContentDraftCreationStatus;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 동일한 서비스 계약을 H2와 실제 MySQL의 별도 연결에서 검사한다. */
class ContentDraftIdempotencyTest extends ContentDraftFixture {
    @Test
    @DisplayName("응답 유실 재시도는 새 전송에서도 같은 원고의 현재 수정 판을 반환한다")
    void returnsCurrentRevisionWithoutRestoringInitialBody() {
        var id = UUID.randomUUID();
        var input = inputDraft("최초", false);
        var first = drafts.createIdempotent(owner, scope("call_1"), id, input);
        var second = drafts.createIdempotent(owner, scope("call_1"), id, input);
        assertThat(second.draft().draftId()).isEqualTo(first.draft().draftId());
        drafts.revise(owner, conversation.id(), first.draft().draftId(), 1, inputDraft("현재", false));
        var retried = drafts.createIdempotent(owner, scope("call_2"), id, input);
        assertThat(retried.draft().revision()).isEqualTo(2);
        assertThat(retried.draft().title()).isEqualTo("현재");
        assertThat(draftRows.count()).isEqualTo(1);
        assertThat(revisionRows.count()).isEqualTo(2);
        assertThat(creationRequests.count()).isEqualTo(1);
        assertThat(creationCalls.count()).isEqualTo(2);
    }


    @Test
    @DisplayName("최초 본문 변조와 같은 전송의 요청 교체는 typed 충돌이고 새 turn 재생은 맥락 오류다")
    void rejectsChangedBodyRequestAndOriginWithoutNewDrafts() {
        var id = UUID.randomUUID();
        drafts.createIdempotent(owner, scope("call_1"), id, inputDraft("최초", false));
        for (var changed : List.of(id, UUID.randomUUID())) {
            assertThatThrownBy(() -> drafts.createIdempotent(owner, scope("call_1"), changed, inputDraft("변조", false)))
                .isInstanceOfSatisfying(ContentDraftException.class, ex -> {
                    assertThat(ex.code()).isEqualTo(ErrorCode.CONTENT_DRAFT_CONFLICT);
                    assertThat(ex.currentRevision()).isEqualTo(1);
                });
        }
        var other = new ContentDraftCreationContext(owner.id(), conversation.id(), "profile", 100L, 100L, "root", "root", "call_1");
        assertThatThrownBy(() -> drafts.createIdempotent(owner, other, id, inputDraft("최초", false)))
            .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.MCP_CALL_CONTEXT_INVALID));
        assertThat(draftRows.count()).isEqualTo(1);
        assertThat(revisionRows.count()).isEqualTo(1);
    }


    @Test
    @DisplayName("같은 origin의 다른 session에 있는 같은 도구 번호는 독립된 전송이다")
    void separatesCallsInDifferentSessions() {
        var first = drafts.createIdempotent(owner, scope("call_1"), UUID.randomUUID(), inputDraft("첫", false));
        var child = new ContentDraftCreationContext(owner.id(), conversation.id(), "profile", 99L, 99L, "root", "child", "call_1");
        var second = drafts.createIdempotent(owner, child, UUID.randomUUID(), inputDraft("둘", false));
        assertThat(second.draft().draftId()).isNotEqualTo(first.draft().draftId());
        assertThat(creationCalls.count()).isEqualTo(2);
    }


    @Test
    @DisplayName("만료 경계와 cleaner 재시작 뒤 tombstone은 hash와 참조 없이 재생성을 막는다")
    void preservesGoneTombstonesAfterExpiryAndRestart() {
        var id = UUID.randomUUID();
        var input = inputDraft("만료", false);
        var first = drafts.createIdempotent(owner, scope("call_1"), id, input);
        clock.set(first.expiresAt());
        assertGone(() -> drafts.createIdempotent(owner, scope("call_2"), id, input));
        cleaner.cleanExpired(clock.instant());
        cleaner.cleanExpired(clock.instant());
        assertGone(() -> drafts.createIdempotent(owner, scope("call_3"), id, input));
        var row = creationRequests.findAll().getFirst();
        assertThat(row.status()).isEqualTo(ContentDraftCreationStatus.GONE);
        assertThat(row.requestHash()).isNull();
        assertThat(row.draftPublicId()).isNull();
        assertThat(draftRows.count()).isEqualTo(1);
        assertThat(creationCalls.count()).isEqualTo(1);
    }


    @Test
    @DisplayName("참조 STALE는 소비 키를 유지하고 기존 CAS로 회복하면 현재 판을 반환한다")
    void keepsStaleKeyUntilCasRepairsReferences() {
        var id = UUID.randomUUID();
        var input = inputDraft("사진", true);
        var first = drafts.createIdempotent(owner, scope("call_1"), id, input);
        attachmentCleaner.delete(photo, NOW);
        assertThatThrownBy(() -> drafts.createIdempotent(owner, scope("call_2"), id, input))
            .isInstanceOfSatisfying(ContentDraftException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.CONTENT_DRAFT_STALE));
        drafts.revise(owner, conversation.id(), first.draft().draftId(), 1, inputDraft("회복", false));
        assertThat(drafts.createIdempotent(owner, scope("call_3"), id, input).draft().title()).isEqualTo("회복");
        assertThat(draftRows.count()).isEqualTo(1);
    }


    @Test
    @DisplayName("별도 연결의 동시 같은 생성은 한 원고 한 revision 한 대응만 커밋한다")
    void acceptsOneDraftAcrossConcurrentTransactions() throws Exception {
        var id = UUID.randomUUID();
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var pending = List.of("call_1", "call_2").stream().map(call -> pool.submit(() -> {
                ready.countDown();
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return drafts.createIdempotent(owner, scope(call), id, inputDraft("동시", false)).draft().draftId();
            })).toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(pending.get(0).get(10, TimeUnit.SECONDS)).isEqualTo(pending.get(1).get(10, TimeUnit.SECONDS));
        }
        assertThat(draftRows.count()).isEqualTo(1);
        assertThat(revisionRows.count()).isEqualTo(1);
        assertThat(creationRequests.count()).isEqualTo(1);
        assertThat(creationCalls.count()).isEqualTo(2);
    }


    @Test
    @DisplayName("암호화와 asset 대응 insert 및 commit 실패는 다섯 표 전체를 rollback하고 같은 키로 회복한다")
    void rollsBackEveryParticipantBeforeRetry() {
        var refs = new ContentDraftReferences(attachments, store, observations, bodies, clock, json);
        for (String failure : List.of("seal", "asset", "request", "call", "commit")) {
            var failedAssets = Mockito.mock(ContentDraftAssetRepository.class,
                AdditionalAnswers.delegatesTo(assetRows));
            var failedRequests = Mockito.mock(ContentDraftCreationRequestRepository.class,
                AdditionalAnswers.delegatesTo(creationRequests));
            var failedCalls = Mockito.mock(ContentDraftCreationCallRepository.class,
                AdditionalAnswers.delegatesTo(creationCalls));
            var failureException = new DataAccessResourceFailureException("synthetic rollback");
            switch (failure) {
                case "asset" -> Mockito.doThrow(failureException).when(failedAssets).save(ArgumentMatchers.any());
                case "request" -> Mockito.doThrow(failureException).when(failedRequests).saveAndFlush(ArgumentMatchers.any());
                case "call" -> Mockito.doThrow(failureException).when(failedCalls).saveAndFlush(ArgumentMatchers.any());
                default -> {
                }
            }
            var localBodies = new ContentDraftBodies(cipher) {
                @Override
                public SealedText seal(ContentDraft draft, long revision, String text) {
                    if (failure.equals("seal")) {
                        throw failureException;
                    }
                    if (failure.equals("commit")) {
                        TransactionSynchronizationManager.registerSynchronization(
                            new TransactionSynchronization() {
                                @Override
                                public void beforeCommit(boolean readOnly) {
                                    throw failureException;
                                }
                            });
                    }
                    return draftBodies.seal(draft, revision, text);
                }
            };
            var local = new ContentDraftService(access, messages, mutations, draftRows,
                revisionRows, failedAssets, refs, localBodies, reader, json, clock, failedRequests, failedCalls);
            var id = UUID.randomUUID();
            var input = inputDraft("rollback", true);
            assertThatThrownBy(() -> local.createIdempotent(owner, scope("call_1"), id, input)).isInstanceOf(DataAccessResourceFailureException.class);
            assertThat(draftRows.count()).isZero();
            assertThat(revisionRows.count()).isZero();
            assertThat(assetRows.count()).isZero();
            assertThat(creationRequests.count()).isZero();
            assertThat(creationCalls.count()).isZero();
            drafts.createIdempotent(owner, scope("call_1"), id, input);
            assertThat(draftRows.count()).isEqualTo(1);
            creationCalls.deleteAll();
            creationRequests.deleteAll();
            assetRows.deleteAll();
            revisionRows.deleteAll();
            draftRows.deleteAll();
        }
    }


    @Test
    @DisplayName("대화 purge는 두 대응을 tombstone으로 남기고 원고와 본문을 물리 삭제한다")
    void preservesConsumedIdentityAfterConversationPurge() {
        var id = UUID.randomUUID();
        drafts.createIdempotent(owner, scope("call_1"), id, inputDraft("purge", false));
        jdbc.update("update conversation set deleted_at=? where id=?", NOW, conversation.id());
        purger.purgeDue(NOW);
        assertThat(draftRows.count()).isZero();
        assertThat(revisionRows.count()).isZero();
        assertThat(creationRequests.count()).isEqualTo(1);
        assertThat(creationCalls.count()).isEqualTo(1);
        assertThat(creationRequests.findAll().getFirst().status()).isEqualTo(ContentDraftCreationStatus.GONE);
        assertThat(creationRequests.findAll().getFirst().requestHash()).isNull();
        assertThat(creationRequests.findAll().getFirst().draftPublicId()).isNull();
        assertThatThrownBy(() -> drafts.createIdempotent(owner, scope("call_2"), id, inputDraft("purge", false)))
            .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.CONVERSATION_NOT_FOUND));
    }


    @Test
    @DisplayName("동시 같은 키의 다른 본문은 성공 하나와 충돌 하나이고 최초 본문을 보존한다")
    void rejectsConcurrentDifferentBodiesWithoutOverwriting() throws Exception {
        var id = UUID.randomUUID();
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var pending = List.of("첫", "둘").stream().map(title -> pool.submit(() -> {
                ready.countDown();
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                try {
                    drafts.createIdempotent(owner, scope(title), id, inputDraft(title, false));
                    return "accepted";
                } catch (ContentDraftException ex) {
                    assertThat(ex.code()).isEqualTo(ErrorCode.CONTENT_DRAFT_CONFLICT);
                    assertThat(ex.currentRevision()).isEqualTo(1);
                    return "conflict";
                }
            })).toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(pending.get(0).get(10, TimeUnit.SECONDS), pending.get(1).get(10, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder("accepted", "conflict");
        }
        assertThat(draftRows.count()).isEqualTo(1);
        assertThat(revisionRows.count()).isEqualTo(1);
        assertThat(creationRequests.count()).isEqualTo(1);
        assertThat(creationCalls.count()).isEqualTo(1);
    }


    private ContentDraftCreationContext scope(String call) {
        return new ContentDraftCreationContext(owner.id(), conversation.id(), "profile", 99L, 99L, "root", "root", call);
    }


    private static void assertGone(Runnable work) {
        assertThatThrownBy(work::run).isInstanceOfSatisfying(ContentDraftException.class, ex -> {
            assertThat(ex.code()).isEqualTo(ErrorCode.CONTENT_DRAFT_GONE);
            assertThat(ex.currentRevision()).isNull();
        });
    }
}
