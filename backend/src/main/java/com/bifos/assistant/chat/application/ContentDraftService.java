package com.bifos.assistant.chat.application;

import com.bifos.assistant.chat.application.model.CanonicalDraft;
import com.bifos.assistant.chat.application.model.ChatContentMutationTarget;
import com.bifos.assistant.chat.application.model.ContentDraftInput;
import com.bifos.assistant.chat.application.model.ContentDraftSummary;
import com.bifos.assistant.chat.application.model.ContentDraftView;
import com.bifos.assistant.chat.domain.ContentDraftException;
import com.bifos.assistant.chat.domain.entity.ContentDraft;
import com.bifos.assistant.chat.domain.entity.ContentDraftAsset;
import com.bifos.assistant.chat.domain.entity.ContentDraftRevision;
import com.bifos.assistant.chat.domain.type.ConversationPurpose;
import com.bifos.assistant.chat.domain.type.ContentDraftRevisionStatus;
import com.bifos.assistant.chat.infra.ChatMessageRepository;
import com.bifos.assistant.chat.infra.ContentDraftAssetRepository;
import com.bifos.assistant.chat.infra.ContentDraftBodies;
import com.bifos.assistant.chat.infra.ContentDraftRepository;
import com.bifos.assistant.chat.infra.ContentDraftRevisionRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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


    public ContentDraftView create(CurrentUser user, Long conversationId, ContentDraftInput input) {
        requireWritable(user, conversationId);
        var accepted = reader.read(json.valueToTree(input));
        UUID id = UUID.randomUUID();
        mutations.run(user.id(), target(conversationId), () -> {
            requireWritable(user, conversationId);
            var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            var draft = new ContentDraft(id.toString(), conversationId, user.id(), now, now.plus(Duration.ofDays(30)));
            var prepared = references.prepare(draft, accepted.blocks());
            var expiry = references.expiry(prepared, draft.expiresAt());
            draft = new ContentDraft(id.toString(), conversationId, user.id(), now, expiry);
            save(draft, 1, accepted, prepared);
            return null;
        });
        return read(user, conversationId, id, null);
    }


    public ContentDraftView revise(CurrentUser user, Long conversationId, UUID id, long expectedRevision, ContentDraftInput input) {
        requireWritable(user, conversationId);
        if (expectedRevision < 1 || expectedRevision >= ContentDraftInputReader.MAX_REVISION) {
            throw ContentDraftInputReader.invalid();
        }
        var accepted = reader.read(json.valueToTree(input));
        var error = mutations.run(user.id(), target(conversationId), () -> {
            requireWritable(user, conversationId);
            var draft = requireDraft(user, conversationId, id);
            if (!draft.expiresAt().isAfter(clock.instant())) {
                revisions.expire(draft.id());
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
        return read(user, conversationId, id, null);
    }


    public ContentDraftView read(CurrentUser user, Long conversationId, UUID id, Long revision) {
        access.requireOwn(user, conversationId);
        if (revision != null && (revision < 1 || revision > ContentDraftInputReader.MAX_REVISION)) {
            throw ContentDraftInputReader.invalid();
        }
        var result = mutations.run(user.id(), target(conversationId), () -> {
            var conversation = access.requireOwn(user, conversationId);
            var draft = requireDraft(user, conversationId, id);
            var row = revisions.findByDraftIdAndRevision(draft.id(), revision == null ? draft.currentRevision() : revision)
                .orElseThrow(ContentDraftService::notFound);
            var error = check(draft, row);
            if (error != null) {
                return new ReadResult(null, error);
            }
            var input = open(draft, row);
            var canonical = new CanonicalDraft(1, id, row.revision(), conversation.publicId(),
                input.title(), input.category(), input.tags(), input.blocks());
            error = check(draft, row);
            return new ReadResult(error == null ? new ContentDraftView(canonical, "DRAFTING", draft.expiresAt()) : null, error);
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


    private ContentDraftException check(ContentDraft draft, ContentDraftRevision row) {
        if (!draft.expiresAt().isAfter(clock.instant()) || row.status() == ContentDraftRevisionStatus.EXPIRED) {
            revisions.expire(draft.id());
            return new ContentDraftException(ErrorCode.CONTENT_DRAFT_GONE, null);
        }
        if (row.status() == ContentDraftRevisionStatus.STALE
            || !references.valid(draft, assets.findByRevisionIdOrderByPositionAsc(row.id()), true)) {
            revisions.invalidate(row.id());
            return new ContentDraftException(ErrorCode.CONTENT_DRAFT_STALE, draft.currentRevision());
        }
        return null;
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
