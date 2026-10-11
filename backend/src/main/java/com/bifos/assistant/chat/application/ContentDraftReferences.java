package com.bifos.assistant.chat.application;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import com.bifos.assistant.chat.domain.ChatAttachment;
import com.bifos.assistant.chat.application.model.MediaObservationInput;
import com.bifos.assistant.chat.application.model.ObservationProvenance;
import com.bifos.assistant.chat.domain.entity.ContentDraft;
import com.bifos.assistant.chat.domain.entity.ContentDraftAsset;
import com.bifos.assistant.chat.domain.type.ObservationStatus;
import com.bifos.assistant.chat.infra.AttachmentFingerprint;
import com.bifos.assistant.chat.infra.AttachmentStore;
import com.bifos.assistant.chat.infra.ChatAttachmentRepository;
import com.bifos.assistant.chat.infra.ContentDraftBodies;
import com.bifos.assistant.chat.infra.MediaObservationBodies;
import com.bifos.assistant.chat.infra.MediaObservationRepository;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.core.JacksonException;

/** 쓰기만 원본을 읽는다. 읽기·정리는 확정된 DB 대응을 검사하여 일시 장애와 구분한다. */
@Component
@RequiredArgsConstructor
public class ContentDraftReferences {
    private static final Set<ObservationStatus> READABLE = Set.of(
        ObservationStatus.SUCCEEDED, ObservationStatus.PARTIAL, ObservationStatus.NEEDS_REVIEW);
    private final ChatAttachmentRepository attachments;
    private final AttachmentStore store;
    private final MediaObservationRepository observations;
    private final MediaObservationBodies bodies;
    private final Clock clock;
    private final ObjectMapper json;

    public List<ContentDraftAsset> prepare(ContentDraft draft, JsonNode blocks) {
        var result = new ArrayList<ContentDraftAsset>();
        var fingerprints = new HashMap<Long, String>();
        for (int position = 0; position < blocks.size(); position++) {
            var block = blocks.get(position);
            if (!"image".equals(block.path("type").asString())) {
                continue;
            }
            Long id = ContentDraftInputReader.id(block.get("assetId"));
            ChatAttachment attachment = attachments.findById(id).orElseThrow(ContentDraftReferences::gone);
            if (!usable(draft, attachment)) {
                throw gone();
            }
            String fingerprint = fingerprints.computeIfAbsent(id, ignored -> fingerprint(attachment));
            var ref = block.get("observationRef");
            Long observationId = ref == null ? null : ContentDraftInputReader.id(ref.get("id"));
            Long revision = ref == null ? null : ContentDraftInputReader.revision(ref.get("revision"));
            var asset = new ContentDraftAsset(null, position, id, fingerprint, observationId, revision);
            if (!validObservation(draft, asset, true)) {
                throw new ContentDraftException(ErrorCode.CONTENT_DRAFT_STALE, draft.id() == null ? null : draft.currentRevision());
            }
            result.add(asset);
        }
        return result;
    }

    public boolean valid(ContentDraft draft, List<ContentDraftAsset> assets, boolean openObservation) {
        for (var asset : assets) {
            var attachment = attachments.findById(asset.attachmentId()).orElse(null);
            if (attachment == null || !usable(draft, attachment) || !validObservation(draft, asset, openObservation)) {
                return false;
            }
        }
        return true;
    }

    public Instant expiry(List<ContentDraftAsset> assets, Instant initial) {
        var result = initial;
        for (var asset : assets) {
            var expiry = attachments.findById(asset.attachmentId()).orElseThrow(ContentDraftReferences::gone).expiresAt();
            if (expiry.isBefore(result)) {
                result = expiry;
            }
        }
        return result;
    }

    private boolean usable(ContentDraft draft, ChatAttachment row) {
        return draft.conversationId().equals(row.conversationId()) && draft.ownerUserId().equals(row.uploadedByUserId())
            && row.messageId() != null && row.deletionRequestedAt() == null && row.deletedAt() == null
            && row.expiresAt().isAfter(clock.instant());
    }

    private boolean validObservation(ContentDraft draft, ContentDraftAsset asset, boolean open) {
        if (asset.observationId() == null) {
            return true;
        }
        var row = observations.findById(asset.observationId()).orElse(null);
        if (row == null || !row.attachmentId().equals(asset.attachmentId())
            || !row.conversationId().equals(draft.conversationId()) || !row.ownerUserId().equals(draft.ownerUserId())
            || row.revision() != asset.observationRevision() || !row.sourceFingerprint().equals(asset.sourceFingerprint())
            || !row.expiresAt().isAfter(clock.instant()) || !READABLE.contains(row.status()) || row.body() == null) {
            return false;
        }
        if (open) {
            String plain = bodies.open(row, draft.ownerUserId()).orElseThrow(ContentDraftBodies::unavailable);
            try {
                if (plain.getBytes(StandardCharsets.UTF_8).length > MediaObservationInput.MAX_BODY_BYTES) {
                    return false;
                }
                var body = json.readValue(plain, MediaObservationInput.class);
                if (body == null || body.status() != row.status()) {
                    return false;
                }
                body.validate(new ObservationProvenance(row.provenanceKind(), row.originExecutionId(), row.provider(),
                    row.providerVersion(), row.model(), row.modelVersion(), row.schemaVersion(), row.promptVersion(), row.createdAt()));
            } catch (JacksonException | ApiException ex) {
                return false;
            }
        }
        return true;
    }

    private String fingerprint(ChatAttachment row) {
        try {
            String fingerprint = AttachmentFingerprint.read(store, row);
            if (fingerprint == null || !row.expiresAt().isAfter(clock.instant())) {
                throw gone();
            }
            return fingerprint;
        } catch (IOException | UncheckedIOException ex) {
            throw ContentDraftBodies.unavailable();
        }
    }

    private static ApiException gone() {
        return new ApiException(ErrorCode.ATTACHMENT_GONE, "첨부를 사용할 수 없습니다.");
    }
}
