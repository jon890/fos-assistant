package com.bifos.assistant.chat.contentdraft.application;

import com.bifos.assistant.chat.attachment.application.ChatContentMutationCoordinator;
import com.bifos.assistant.chat.attachment.application.MediaPipelineEvents;
import com.bifos.assistant.chat.conversation.application.ConversationAccess;
import com.bifos.assistant.chat.contentdraft.application.dto.ContentDraftCreationContext;
import com.bifos.assistant.chat.contentdraft.domain.entity.ContentDraftCreationRequest;
import com.bifos.assistant.chat.contentdraft.domain.entity.ContentDraftCreationCall;
import com.bifos.assistant.chat.contentdraft.domain.type.ContentDraftCreationStatus;
import com.bifos.assistant.chat.contentdraft.infra.ContentDraftCreationRequestRepository;
import com.bifos.assistant.chat.contentdraft.infra.ContentDraftCreationCallRepository;
import com.bifos.assistant.chat.contentdraft.application.dto.CanonicalDraft;
import com.bifos.assistant.chat.attachment.application.dto.ChatContentMutationTarget;
import com.bifos.assistant.chat.contentdraft.application.dto.ContentDraftInput;
import com.bifos.assistant.chat.contentdraft.application.dto.ContentDraftSummary;
import com.bifos.assistant.chat.contentdraft.application.dto.ContentDraftView;
import com.bifos.assistant.chat.contentdraft.domain.ContentDraftException;
import com.bifos.assistant.chat.contentdraft.domain.entity.ContentDraft;
import com.bifos.assistant.chat.contentdraft.domain.entity.ContentDraftAsset;
import com.bifos.assistant.chat.contentdraft.domain.entity.ContentDraftRevision;
import com.bifos.assistant.chat.conversation.domain.type.ConversationPurpose;
import com.bifos.assistant.chat.contentdraft.domain.type.ContentDraftRevisionStatus;
import com.bifos.assistant.chat.conversation.infra.ChatMessageRepository;
import com.bifos.assistant.chat.contentdraft.infra.ContentDraftAssetRepository;
import com.bifos.assistant.chat.contentdraft.infra.ContentDraftBodies;
import com.bifos.assistant.chat.contentdraft.infra.ContentDraftRepository;
import com.bifos.assistant.chat.contentdraft.infra.ContentDraftRevisionRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import org.springframework.dao.DataIntegrityViolationException;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** 원고 수락과 삭제·만료 장벽을 같은 사용자 잠금으로 직렬화한다. */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class ContentDraftService {
    private final ConversationAccess access;
    private final ChatMessageRepository messages;
    private final ChatContentMutationCoordinator mutations;
    private final ContentDraftRepository drafts;
    private final ContentDraftRevisionRepository revisions;
    private final ContentDraftAssetRepository assets;
    private final ContentDraftReferences references;
    private final ContentDraftBodies bodies;
    private final ContentDraftInputReader reader;
    private final ObjectMapper json;
    private final Clock clock;
    private final ContentDraftCreationRequestRepository creationRequests;
    private final ContentDraftCreationCallRepository creationCalls;


    public ContentDraftView create(CurrentUser user, Long conversationId, ContentDraftInput input) {
        requireWritable(user, conversationId);
        var accepted = reader.read(json.valueToTree(input));
        ContentDraftInputReader.requireUniquePhotos(accepted);
        UUID id = UUID.randomUUID();
        mutations.run(user.id(), target(conversationId), () -> {
            requireWritable(user, conversationId);
            createLocked(user, conversationId, id, accepted);
            return null;
        });
        return read(user, conversationId, id, 1L);
    }


    /** 원고와 생성·전송 대응을 같은 트랜잭션으로 저장한다. */
    public ContentDraftView createIdempotent(CurrentUser user, ContentDraftCreationContext context,
        UUID requestId, ContentDraftInput input) {
        if (!user.id().equals(context.ownerUserId())) {
            throw invalidContext();
        }
        requireWritable(user, context.conversationId());
        var accepted = reader.read(json.valueToTree(input));
        ContentDraftInputReader.requireUniquePhotos(accepted);
        byte[] hash = ContentDraftCreationContext.requestHash(accepted);
        ReadResult result;
        try {
            result = mutations.run(user.id(), target(context.conversationId()),
                () -> createIdempotentLocked(user, context, requestId, accepted, hash, true));
        } catch (DataIntegrityViolationException ex) {
            // unique 경합에서 실패한 트랜잭션을 끝낸 뒤 새 트랜잭션으로 한 번만 조회한다.
            try {
                result = mutations.run(user.id(), target(context.conversationId()),
                    () -> createIdempotentLocked(user, context, requestId, accepted, hash, false));
            } catch (DataIntegrityViolationException retry) {
                throw ContentDraftBodies.unavailable();
            }
        }
        if (result.error() != null) {
            throw result.error();
        }
        access.requireOwn(user, context.conversationId());
        if (!result.view().expiresAt().isAfter(clock.instant())) {
            throw new ContentDraftException(ErrorCode.CONTENT_DRAFT_GONE, null);
        }
        return result.view();
    }


    private ReadResult createIdempotentLocked(CurrentUser user, ContentDraftCreationContext context,
        UUID requestId, ContentDraftInput input, byte[] hash, boolean allowCreate) {
        requireWritable(user, context.conversationId());
        var request = creationRequests.findByIdentityKey(context.creationIdentityKey(requestId)).orElse(null);
        var keyedRequest = creationRequests.findByCreationKey(context.creationKey(requestId)).orElse(null);
        if (request != null && (!context.matches(request) || !request.requestId().equals(requestId.toString())
            || !Arrays.equals(request.creationKey(), context.creationKey(requestId)))
            || keyedRequest != null && (request == null || !keyedRequest.id().equals(request.id()))) {
            throw invalidContext();
        }
        var call = creationCalls.findByIdentityKey(context.callIdentityKey()).orElse(null);
        var keyedCall = creationCalls.findByCallKey(context.callKey()).orElse(null);
        if (call != null && (!Arrays.equals(call.callKey(), context.callKey())
            || !call.sessionId().equals(context.sessionId()) || !call.toolCallId().equals(context.toolCallId()))
            || keyedCall != null && (call == null || !keyedCall.id().equals(call.id()))) {
            throw invalidContext();
        }
        var prior = call == null ? request : creationRequests.findById(call.creationRequestId()).orElseThrow(ContentDraftService::invalidContext);
        if (prior != null) {
            if (!context.matches(prior)) {
                throw invalidContext();
            }
            if (prior.status() == ContentDraftCreationStatus.GONE) {
                return new ReadResult(null, new ContentDraftException(ErrorCode.CONTENT_DRAFT_GONE, null));
            }
            var draft = requireDraft(user, context.conversationId(), UUID.fromString(prior.draftPublicId()));
            var current = readLocked(user, context.conversationId(), draft, null);
            if (current.error() != null) {
                return current;
            }
            if (!prior.requestId().equals(requestId.toString()) || !Arrays.equals(prior.requestHash(), hash)
                || request != null && !prior.id().equals(request.id())) {
                throw new ContentDraftException(ErrorCode.CONTENT_DRAFT_CONFLICT, draft.currentRevision());
            }
            if (call == null) {
                saveCall(context, prior);
            }
            return current;
        }
        if (!allowCreate) {
            throw ContentDraftBodies.unavailable();
        }
        UUID id = UUID.randomUUID();
        var draft = createLocked(user, context.conversationId(), id, input);
        request = creationRequests.saveAndFlush(new ContentDraftCreationRequest(context.creationKey(requestId),
            context.creationIdentityKey(requestId), context.ownerUserId(), context.conversationId(), context.profileName(),
            context.originExecutionId(), context.rootExecutionId(), context.rootSessionId(), requestId.toString(), hash,
            id.toString()));
        saveCall(context, request);
        return readLocked(user, context.conversationId(), draft, null);
    }


    private void saveCall(ContentDraftCreationContext context, ContentDraftCreationRequest request) {
        creationCalls.saveAndFlush(new ContentDraftCreationCall(context.callKey(), context.callIdentityKey(),
            request.id(), context.sessionId(), context.toolCallId()));
    }


    private ContentDraft createLocked(CurrentUser user, Long conversationId, UUID id, ContentDraftInput input) {
        var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        var draft = new ContentDraft(id.toString(), conversationId, user.id(), now.plus(Duration.ofDays(30)));
        var prepared = references.prepare(draft, input.blocks());
        draft = new ContentDraft(id.toString(), conversationId, user.id(), references.expiry(prepared, draft.expiresAt()));
        save(draft, 1, input, prepared);
        return draft;
    }


    private static ApiException invalidContext() {
        return new ApiException(ErrorCode.MCP_CALL_CONTEXT_INVALID, "call context is invalid");
    }


    public ContentDraftView revise(CurrentUser user, Long conversationId, UUID id, long expectedRevision, ContentDraftInput input) {
        requireWritable(user, conversationId);
        if (expectedRevision < 1 || expectedRevision >= ContentDraftInputReader.MAX_REVISION) {
            throw ContentDraftInputReader.invalid();
        }
        var accepted = reader.read(json.valueToTree(input));
        ContentDraftInputReader.requireUniquePhotos(accepted);
        var error = mutations.run(user.id(), target(conversationId), () -> {
            requireWritable(user, conversationId);
            var draft = requireDraft(user, conversationId, id);
            if (!draft.expiresAt().isAfter(clock.instant())) {
                expire(draft);
                return new ContentDraftException(ErrorCode.CONTENT_DRAFT_GONE, null);
            }
            if (draft.currentRevision() != expectedRevision) {
                throw new ContentDraftException(ErrorCode.CONTENT_DRAFT_CONFLICT, draft.currentRevision());
            }
            var prepared = references.prepare(draft, accepted.blocks());
            var expiry = references.expiry(prepared, draft.expiresAt());
            if (drafts.advance(draft.id(), expectedRevision, expectedRevision + 1, expiry) != 1) {
                throw new ContentDraftException(ErrorCode.CONTENT_DRAFT_CONFLICT, draft.currentRevision());
            }
            save(draft, expectedRevision + 1, accepted, prepared);
            return null;
        });
        if (error != null) {
            throw error;
        }
        return read(user, conversationId, id, expectedRevision + 1);
    }


    public ContentDraftView read(CurrentUser user, Long conversationId, UUID id, Long revision) {
        access.requireOwn(user, conversationId);
        if (revision != null && (revision < 1 || revision > ContentDraftInputReader.MAX_REVISION)) {
            throw ContentDraftInputReader.invalid();
        }
        var result = mutations.run(user.id(), target(conversationId), () -> {
            var draft = requireDraft(user, conversationId, id);
            return readLocked(user, conversationId, draft, revision);
        });
        if (result.error() != null) {
            throw result.error();
        }
        access.requireOwn(user, conversationId);
        if (!result.view().expiresAt().isAfter(clock.instant())) {
            throw new ContentDraftException(ErrorCode.CONTENT_DRAFT_GONE, null);
        }
        return result.view();
    }


    public List<ContentDraftSummary> list(CurrentUser user, Long conversationId) {
        access.requireOwn(user, conversationId);
        var result = mutations.run(user.id(), target(conversationId), () -> {
            access.requireOwn(user, conversationId);
            var items = new ArrayList<ContentDraftSummary>();
            for (var draft : drafts.findTop100ByConversationIdOrderByCreatedAtDescIdDesc(conversationId)) {
                if (!draft.ownerUserId().equals(user.id())) {
                    continue;
                }
                var row = revisions.findByDraftIdAndRevision(draft.id(), draft.currentRevision()).orElseThrow(ContentDraftService::notFound);
                var error = check(draft, row);
                String title = error == null ? open(draft, row).title() : null;
                String status = error == null ? "DRAFTING" : error.code() == ErrorCode.CONTENT_DRAFT_GONE ? "EXPIRED" : "STALE";
                items.add(new ContentDraftSummary(UUID.fromString(draft.publicId()), draft.currentRevision(), title, status, draft.expiresAt()));
            }
            return List.copyOf(items);
        });
        access.requireOwn(user, conversationId);
        return result.stream().map(item -> !item.expiresAt().isAfter(clock.instant())
            ? new ContentDraftSummary(item.draftId(), item.revision(), null, "EXPIRED", item.expiresAt()) : item).toList();
    }


    private ReadResult readLocked(CurrentUser user, Long conversationId, ContentDraft draft, Long revision) {
        var conversation = access.requireOwn(user, conversationId);
        var row = revisions.findByDraftIdAndRevision(draft.id(), revision == null ? draft.currentRevision() : revision)
            .orElseThrow(ContentDraftService::notFound);
        var error = check(draft, row);
        if (error != null) {
            return new ReadResult(null, error);
        }
        var input = open(draft, row);
        var canonical = new CanonicalDraft(1, UUID.fromString(draft.publicId()), row.revision(), conversation.publicId(),
            input.title(), input.category(), input.tags(), input.blocks());
        error = check(draft, row);
        return new ReadResult(error == null ? new ContentDraftView(canonical, "DRAFTING", draft.expiresAt()) : null, error);
    }


    private ContentDraftException check(ContentDraft draft, ContentDraftRevision row) {
        if (!draft.expiresAt().isAfter(clock.instant()) || row.status() == ContentDraftRevisionStatus.EXPIRED) {
            expire(draft);
            return new ContentDraftException(ErrorCode.CONTENT_DRAFT_GONE, null);
        }
        if (row.status() == ContentDraftRevisionStatus.STALE
            || !references.valid(draft, assets.findByRevisionIdOrderByPositionAsc(row.id()), true)) {
            revisions.invalidate(row.id());
            return new ContentDraftException(ErrorCode.CONTENT_DRAFT_STALE, draft.currentRevision());
        }
        return null;
    }


    private void expire(ContentDraft draft) {
        revisions.expire(draft.id());
        creationRequests.findByDraftPublicId(draft.publicId()).forEach(ContentDraftCreationRequest::markGone);
    }


    private ContentDraftInput open(ContentDraft draft, ContentDraftRevision row) {
        try {
            return reader.read(json.readTree(bodies.open(draft, row)));
        } catch (JacksonException | ApiException ex) {
            throw ContentDraftBodies.unavailable();
        }
    }


    private void save(ContentDraft draft, long revision, ContentDraftInput input, List<ContentDraftAsset> prepared) {
        var sealed = bodies.seal(draft, revision, json.writeValueAsString(input));
        if (draft.id() == null) {
            drafts.saveAndFlush(draft);
        }
        var row = revisions.saveAndFlush(new ContentDraftRevision(draft.id(), revision,
            clock.instant().truncatedTo(ChronoUnit.MICROS), sealed.content(), sealed.keyId()));
        for (var asset : prepared) {
            assets.save(new ContentDraftAsset(row.id(), asset.position(), asset.attachmentId(), asset.sourceFingerprint(),
                asset.observationId(), asset.observationRevision()));
        }
        if (!draft.expiresAt().isAfter(clock.instant()) || !references.valid(draft, prepared, true)) {
            throw ContentDraftBodies.unavailable();
        }
        MediaPipelineEvents.draftReferenced(draft.conversationId(), draft.publicId(), revision, prepared);
    }


    private ContentDraft requireDraft(CurrentUser user, Long conversationId, UUID id) {
        access.requireOwn(user, conversationId);
        return drafts.findByPublicIdAndConversationIdAndOwnerUserId(id.toString(), conversationId, user.id())
            .orElseThrow(ContentDraftService::notFound);
    }


    private void requireWritable(CurrentUser user, Long conversationId) {
        var conversation = access.requireOwn(user, conversationId);
        if (conversation.purpose() == ConversationPurpose.CHECK
            || conversation.taskId() != null && !messages.existsByConversationId(conversationId)) {
            throw notFound();
        }
    }


    private static ChatContentMutationTarget target(Long conversationId) {
        return new ChatContentMutationTarget(conversationId, List.of());
    }


    private static ApiException notFound() {
        return new ApiException(ErrorCode.CONVERSATION_NOT_FOUND, "this conversation does not exist");
    }


    private record ReadResult(ContentDraftView view, ContentDraftException error) {
    }
}
