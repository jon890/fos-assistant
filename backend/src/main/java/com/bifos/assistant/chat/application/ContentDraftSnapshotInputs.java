package com.bifos.assistant.chat.application;

import java.util.Map;
import java.util.Locale;
import java.util.List;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import com.bifos.assistant.chat.application.model.CanonicalDraft;
import com.bifos.assistant.chat.application.model.ContentDraftInput;
import com.bifos.assistant.chat.domain.entity.ContentDraftAsset;
import tools.jackson.databind.JsonNode;
import com.bifos.assistant.chat.application.model.ContentDraftBindingStamp;
import com.bifos.assistant.chat.application.model.ContentDraftSnapshotInput;
import com.bifos.assistant.chat.domain.ContentDraftException;
import com.bifos.assistant.chat.domain.type.ContentDraftRevisionStatus;
import com.bifos.assistant.chat.infra.AttachmentFingerprint;
import com.bifos.assistant.chat.infra.AttachmentProperties;
import com.bifos.assistant.chat.infra.AttachmentStore;
import com.bifos.assistant.chat.infra.ChatAttachmentRepository;
import com.bifos.assistant.chat.infra.ContentDraftAssetRepository;
import com.bifos.assistant.chat.infra.ContentDraftBodies;
import com.bifos.assistant.chat.infra.ContentDraftRepository;
import com.bifos.assistant.chat.infra.ContentDraftRevisionRepository;
import com.bifos.assistant.chat.infra.MediaObservationBodies;
import com.bifos.assistant.chat.infra.MediaObservationRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.UserAccessPolicy;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import java.time.Clock;
import java.util.HashMap;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** 기존 원고 서비스를 재진입하지 않고 참가 트랜잭션에서 원본을 재검증한다. */
@Component
@RequiredArgsConstructor
public class ContentDraftSnapshotInputs {
    private final ChatContentMutationCoordinator mutations;
    private final ConversationAccess access;
    private final ContentDraftRepository drafts;
    private final ContentDraftRevisionRepository revisions;
    private final ContentDraftAssetRepository assets;
    private final ContentDraftReferences references;
    private final ContentDraftBodies bodies;
    private final ContentDraftInputReader reader;
    private final ChatAttachmentRepository attachments;
    private final AttachmentStore store;
    private final AttachmentProperties properties;
    private final MediaObservationRepository observations;
    private final MediaObservationBodies observationBodies;
    private final AgentExecutionRepository executions;
    private final UserAccessPolicy users;
    private final ObjectMapper json;
    private final Clock clock;


    public Long agent(CurrentUser user, Long conversationId, Long originId) {
        access.requireOwn(user, conversationId);
        if (originId == null || !users.allowed(user.id())) {
            throw ContentDraftSnapshotHash.unavailable();
        }
        var origin = executions.findById(originId).orElseThrow(ContentDraftSnapshotHash::unavailable);
        var root = executions.findById(origin.treeRootId()).orElseThrow(ContentDraftSnapshotHash::unavailable);
        if (!user.id().equals(origin.userId()) || !conversationId.equals(origin.conversationId())
            || !user.id().equals(root.userId()) || !conversationId.equals(root.conversationId())
            || origin.status() == ExecutionStatus.CANCELLED || root.status() == ExecutionStatus.CANCELLED || origin.agentId() == null) {
            throw ContentDraftSnapshotHash.unavailable();
        }
        return origin.agentId();
    }


    public ContentDraftSnapshotInput read(CurrentUser user, Long conversationId, UUID draftId, long revision,
        Long originId, ContentDraftBindingStamp binding) {
        mutations.requireParticipant(conversationId);
        var conversation = access.requireOwn(user, conversationId);
        var draft = drafts.findByPublicIdAndConversationIdAndOwnerUserId(draftId.toString(), conversationId, user.id())
            .orElseThrow(() -> new ApiException(ErrorCode.CONVERSATION_NOT_FOUND, "대화를 찾을 수 없습니다."));
        var row = revisions.findByDraftIdAndRevision(draft.id(), revision).orElseThrow(ContentDraftSnapshotHash::unavailable);
        var links = assets.findByRevisionIdOrderByPositionAsc(row.id());
        if (!draft.expiresAt().isAfter(clock.instant()) || row.status() == ContentDraftRevisionStatus.EXPIRED) {
            throw new ContentDraftException(ErrorCode.CONTENT_DRAFT_GONE, null);
        }
        if (draft.currentRevision() != revision || row.status() != ContentDraftRevisionStatus.VALID || !references.valid(draft, links, true)) {
            throw stale();
        }
        if (!binding.agentId().equals(agent(user, conversationId, originId))
            || !binding.profile().equals(executions.findById(originId).orElseThrow(ContentDraftSnapshotHash::unavailable).profileName())) {
            throw ContentDraftSnapshotHash.unavailable();
        }
        try {
            var input = reader.read(json.readTree(bodies.open(draft, row)));
            var canonical = json.valueToTree(new CanonicalDraft(1, draftId, revision, conversation.publicId(),
                input.title(), input.category(), input.tags(), input.blocks()));
            var resolvedAssets = resolve(user, conversationId, links, input);
            ContentDraftSnapshotHash.compile(canonical, resolvedAssets);
            var expiry = references.expiry(links, draft.expiresAt());
            if (!expiry.isAfter(clock.instant())) {
                throw new ContentDraftException(ErrorCode.CONTENT_DRAFT_GONE, null);
            }
            agent(user, conversationId, originId);
            if (!references.valid(draft, links, true)) {
                throw stale();
            }
            return new ContentDraftSnapshotInput(null, row.id(), originId, binding, canonical, resolvedAssets,
                expiry);
        } catch (IOException | RuntimeException ex) {
            if (ex instanceof ContentDraftException draftError) {
                throw draftError;
            }
            throw ContentDraftSnapshotHash.unavailable();
        }
    }


    private JsonNode resolve(CurrentUser user, Long conversationId, List<ContentDraftAsset> links,
        ContentDraftInput input) throws IOException {
        var resolved = new HashMap<Long, ObjectNode>();
        var order = new HashMap<Long, Integer>();
        attachments.findByConversationIdOrderByMessageIdAscPositionAsc(conversationId).stream()
            .filter(it -> it.messageId() != null).forEach(it -> order.put(it.id(), order.size() + 1));
        for (var link : links) {
            var value = resolveAsset(user, conversationId, link, input, order);
            var previous = resolved.putIfAbsent(link.attachmentId(), value);
            if (previous != null && value.has("observationNote")) {
                if (previous.has("observationNote") && !previous.get("observationNote").equals(value.get("observationNote"))) {
                    throw stale();
                }
                previous.set("observationNote", value.get("observationNote"));
            }
        }
        if (links.size() != input.blocks().valueStream().filter(it -> "image".equals(it.path("type").stringValue())).count()) {
            throw stale();
        }
        var resolvedAssets = json.createArrayNode();
        resolved.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(it -> resolvedAssets.add(it.getValue()));
        return resolvedAssets;
    }


    private ObjectNode resolveAsset(CurrentUser user, Long conversationId, ContentDraftAsset link,
        ContentDraftInput input, Map<Long, Integer> order) throws IOException {
        var block = input.blocks().get(link.position());
        if (block == null || !"image".equals(block.path("type").stringValue())
            || !link.attachmentId().toString().equals(block.path("assetId").stringValue())) {
            throw stale();
        }
        var ref = block.get("observationRef");
        if (ref == null ? link.observationId() != null : !ref.path("id").stringValue().equals(String.valueOf(link.observationId()))
            || ref.path("revision").longValue() != link.observationRevision()) {
            throw stale();
        }
        var attachment = attachments.findById(link.attachmentId()).orElseThrow(ContentDraftSnapshotInputs::stale);
        if (!link.sourceFingerprint().equals(AttachmentFingerprint.read(store, attachment))) {
            throw stale();
        }
        var value = json.createObjectNode().put("assetId", link.attachmentId().toString())
            .put("sourceFingerprint", link.sourceFingerprint()).put("fileName", attachment.storedName())
            .put("photoDir", properties.agentRoot().replaceAll("/+$", "") + "/users/"
                + AttachmentStore.userDirectoryKey(user.id()) + "/" + conversationId)
            .put("ordinal", order.get(link.attachmentId()));
        if (link.observationId() != null) {
            var observation = observations.findById(link.observationId()).orElseThrow(ContentDraftSnapshotInputs::stale);
            var summary = json.readTree(observationBodies.open(observation, user.id()).orElseThrow(ContentDraftSnapshotHash::unavailable)).get("summary");
            if (summary != null && summary.isString()) {
                String note = summary.stringValue();
                value.put("observationNote", note.substring(0, note.offsetByCodePoints(0, Math.min(300, note.codePointCount(0, note.length())))));
            }
        }
        String name = attachment.storedName();
        String directory = value.path("photoDir").stringValue();
        if (!name.matches("(?i)[A-Za-z0-9._-]+\\.(jpg|jpeg|png|webp|gif|heic)") || name.contains("..")
            || name.toLowerCase(Locale.ROOT).endsWith(".small.jpg") || name.getBytes(StandardCharsets.UTF_8).length > 255
            || !directory.startsWith("/") || directory.indexOf(0) >= 0 || List.of(directory.split("/")).contains("..")
            || directory.getBytes(StandardCharsets.UTF_8).length > 4096) {
            throw ContentDraftSnapshotHash.unavailable();
        }
        return value;
    }


    public static ContentDraftException stale() {
        return new ContentDraftException(ErrorCode.CONTENT_DRAFT_STALE, null);
    }
}
