package com.bifos.assistant.chat.application;

import com.bifos.assistant.chat.conversation.application.ConversationAccess;
import com.bifos.assistant.chat.application.model.CanonicalDraft;
import com.bifos.assistant.chat.application.model.ChatContentMutationTarget;
import com.bifos.assistant.chat.application.model.ContentDraftBindingStamp;
import com.bifos.assistant.chat.application.model.ContentDraftSnapshotInput;
import com.bifos.assistant.chat.application.model.ContentDraftSnapshotView;
import com.bifos.assistant.chat.domain.entity.ContentDraftSnapshot;
import com.bifos.assistant.chat.infra.ContentDraftRepository;
import com.bifos.assistant.chat.infra.ContentDraftRevisionRepository;
import com.bifos.assistant.chat.infra.ContentDraftSnapshotRepository;
import com.bifos.assistant.chat.infra.ChatAttachmentRepository;
import com.bifos.assistant.hermes.infra.HttpContentDraftSnapshotClient;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** compiler 앞뒤의 같은 잠금 순서로 현재 입력을 검사하고 참조와 지문만 저장한다. */
@Service
@RequiredArgsConstructor
public class ContentDraftSnapshots {
    private final ConversationAccess access;
    private final ChatContentMutationCoordinator mutations;
    private final ContentDraftSnapshotInputs inputs;
    private final ContentDraftBindingAccess bindings;
    private final ContentDraftSnapshotRepository snapshots;
    private final ContentDraftRevisionRepository revisions;
    private final ContentDraftRepository drafts;
    private final HttpContentDraftSnapshotClient compiler;
    private final ObjectMapper json;
    private final Clock clock;
    private final ChatAttachmentRepository attachments;


    public ContentDraftSnapshotView preview(CurrentUser user, Long conversationId, UUID draftId, long revision,
        Long bindingId, Long originExecutionId) {
        access.requireOwn(user, conversationId);
        var stamp = bindings.prepare(user, inputs.agent(user, conversationId, originExecutionId), bindingId);
        var before = mutations.run(user.id(), target(conversationId), () -> current(user, conversationId, draftId, revision, originExecutionId, stamp));
        var result = compiler.compile(stamp.profile(), stamp.connectorId(), before.canonicalDraft(), before.resolvedAssets());
        ContentDraftSnapshotHash.verify(before.canonicalDraft(), before.resolvedAssets(), result);
        var afterStamp = bindings.prepare(user, stamp.agentId(), stamp.bindingId());
        if (!before.binding().equals(afterStamp)) {
            throw ContentDraftSnapshotInputs.stale();
        }
        try {
            return accept(user, conversationId, draftId, revision, before, result, true);
        } catch (DataIntegrityViolationException ex) {
            // UNIQUE 실패가 끝난 뒤 새 트랜잭션에서 owner 범위의 기존 행만 읽는다.
            return accept(user, conversationId, draftId, revision, before, result, false);
        }
    }


    private ContentDraftSnapshotView accept(CurrentUser user, Long conversationId, UUID draftId, long revision,
        ContentDraftSnapshotInput before, JsonNode result, boolean insert) {
        return mutations.run(user.id(), target(conversationId), () -> {
            var now = current(user, conversationId, draftId, revision, before.originExecutionId(), before.binding());
            if (!now.revisionId().equals(before.revisionId()) || !now.expiresAt().equals(before.expiresAt())
                || !now.canonicalDraft().equals(before.canonicalDraft()) || !now.resolvedAssets().equals(before.resolvedAssets())) {
                throw ContentDraftSnapshotInputs.stale();
            }
            var authorization = ContentDraftAuthorizationHash.fingerprint(user.id(), conversationId, now);
            var row = snapshots.findByRevisionIdAndBindingIdAndSnapshotHashAndAuthorizationFingerprintAndOwnerUserIdAndConversationId(
                now.revisionId(), now.binding().bindingId(), result.path("snapshotHash").stringValue(), authorization, user.id(), conversationId).orElse(null);
            if (row == null) {
                if (!insert) {
                    throw ContentDraftSnapshotHash.unavailable("accept:missing-row");
                }
                var stamp = now.binding();
                row = snapshots.saveAndFlush(new ContentDraftSnapshot(UUID.randomUUID(), now.revisionId(), user.id(), conversationId,
                    now.originExecutionId(), stamp.agentId(), stamp.connectionId(), stamp.bindingId(), stamp.connectionUpdatedAt(), stamp.bindingUpdatedAt(),
                    stamp.policyFingerprint(), 1, "content-compiler-v1", result.path("payloadFingerprint").stringValue(),
                    result.path("snapshotHash").stringValue(), authorization, now.expiresAt()));
            }
            requireStamp(row, now.binding());
            if (!row.originExecutionId().equals(now.originExecutionId()) || !row.expiresAt().equals(now.expiresAt())) {
                throw ContentDraftSnapshotInputs.stale();
            }
            inputs.agent(user, conversationId, row.originExecutionId());
            return view(row, now);
        });
    }


    public ContentDraftSnapshotView readCurrentSnapshot(CurrentUser user, Long conversationId, UUID snapshotId) {
        access.requireOwn(user, conversationId);
        var row = requireOwned(user, conversationId, snapshotId);
        var stamp = bindings.prepare(user, row.agentId(), row.bindingId());
        return mutations.run(user.id(), target(conversationId), () -> {
            var input = requireCurrentSnapshotParticipant(user, conversationId, snapshotId);
            requireStamp(requireOwned(user, conversationId, snapshotId), stamp);
            return view(requireOwned(user, conversationId, snapshotId), input);
        });
    }


    /**
     * DB 권한과 캡처 원본을 검사한다. 호출자는 트랜잭션 밖에서 준비한 정책 포함 stamp를 반환 binding과
     * 명시적으로 대조한 뒤 사용해야 한다. 이 참가 함수는 현재 catalog를 조회하지 않는다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ContentDraftSnapshotInput requireCurrentSnapshotParticipant(CurrentUser user, Long conversationId, UUID snapshotId) {
        mutations.requireParticipant(conversationId);
        access.requireOwn(user, conversationId);
        var row = requireOwned(user, conversationId, snapshotId);
        var current = bindings.requireCurrent(user, row.agentId(), row.bindingId());
        var stamp = new ContentDraftBindingStamp(current.agentId(), row.connectionId(), row.bindingId(), row.connectionUpdatedAt(),
            row.bindingUpdatedAt(), current.profile(), current.connectorId(), row.policyFingerprint());
        bindings.requireUnchanged(user, stamp);
        var revision = revisions.findById(row.revisionId()).orElseThrow(ContentDraftSnapshotInputs::stale);
        var draft = drafts.findById(revision.draftId()).orElseThrow(ContentDraftSnapshotInputs::stale);
        var input = current(user, conversationId, UUID.fromString(draft.publicId()), revision.revision(), row.originExecutionId(), stamp);
        var result = ContentDraftSnapshotHash.compile(input.canonicalDraft(), input.resolvedAssets());
        if (!row.expiresAt().isAfter(clock.instant()) || !row.snapshotHash().equals(result.path("snapshotHash").stringValue())
            || !row.payloadFingerprint().equals(result.path("payloadFingerprint").stringValue())
            || !row.authorizationFingerprint().equals(ContentDraftAuthorizationHash.fingerprint(user.id(), conversationId, input))) {
            throw ContentDraftSnapshotInputs.stale();
        }
        return new ContentDraftSnapshotInput(row.publicId(), input.revisionId(), input.originExecutionId(), stamp,
            input.canonicalDraft(), input.resolvedAssets(), row.expiresAt());
    }


    private ContentDraftSnapshotInput current(CurrentUser user, Long conversationId, UUID draftId, long revision,
        Long originId, ContentDraftBindingStamp stamp) {
        bindings.requireUnchanged(user, stamp);
        var input = inputs.read(user, conversationId, draftId, revision, originId, stamp);
        bindings.requireUnchanged(user, stamp);
        return input;
    }


    private ContentDraftSnapshot requireOwned(CurrentUser user, Long conversationId, UUID snapshotId) {
        return snapshots.findByPublicIdAndOwnerUserIdAndConversationId(snapshotId, user.id(), conversationId)
            .orElseThrow(() -> new ApiException(ErrorCode.CONVERSATION_NOT_FOUND, "대화를 찾을 수 없습니다."));
    }


    private static void requireStamp(ContentDraftSnapshot row, ContentDraftBindingStamp stamp) {
        if (!row.agentId().equals(stamp.agentId()) || !row.bindingId().equals(stamp.bindingId())
            || !row.connectionId().equals(stamp.connectionId()) || !row.bindingUpdatedAt().equals(stamp.bindingUpdatedAt())
            || !row.connectionUpdatedAt().equals(stamp.connectionUpdatedAt()) || !row.policyFingerprint().equals(stamp.policyFingerprint())) {
            throw ContentDraftSnapshotInputs.stale();
        }
    }


    private ContentDraftSnapshotView view(ContentDraftSnapshot row, ContentDraftSnapshotInput input) {
        var canonical = json.treeToValue(input.canonicalDraft(), CanonicalDraft.class);
        var assetIds = input.resolvedAssets().valueStream().map(it -> it.path("assetId").stringValue()).toList();
        var omitted = attachments.findByConversationIdOrderByMessageIdAscPositionAsc(row.conversationId()).stream()
            .filter(it -> row.ownerUserId().equals(it.uploadedByUserId()) && it.messageId() != null
                && it.deletedAt() == null && it.deletionRequestedAt() == null && it.expiresAt().isAfter(clock.instant()))
            .map(it -> it.id().toString()).filter(id -> !assetIds.contains(id)).toList();
        return new ContentDraftSnapshotView(row.publicId(), canonical.draftId(), canonical.revision(), row.snapshotHash(), canonical,
            assetIds, omitted, row.expiresAt());
    }


    private static ChatContentMutationTarget target(Long conversationId) {
        return new ChatContentMutationTarget(conversationId, List.of());
    }
}
