package com.bifos.assistant.chat.application;

import com.bifos.assistant.chat.application.model.ChatContentMutationTarget;
import com.bifos.assistant.chat.application.model.MediaObservationInput;
import com.bifos.assistant.chat.application.model.MediaObservationView;
import com.bifos.assistant.chat.application.model.ObservationProvenance;
import com.bifos.assistant.chat.domain.ChatAttachment;
import com.bifos.assistant.chat.domain.MediaObservation;
import com.bifos.assistant.chat.domain.MediaObservationRequest;
import com.bifos.assistant.chat.domain.type.ObservationProvenanceKind;
import com.bifos.assistant.chat.domain.type.ObservationStatus;
import com.bifos.assistant.chat.infra.AttachmentStore;
import com.bifos.assistant.chat.infra.ChatAttachmentRepository;
import com.bifos.assistant.chat.infra.MediaObservationBodies;
import com.bifos.assistant.chat.infra.MediaObservationRepository;
import com.bifos.assistant.chat.infra.MediaObservationRequestRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.shared.util.Sha256;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** 원본과 현재 권한을 검사하고 삭제 장벽 안에서 불변 revision과 요청 alias를 함께 저장한다. */
@Service
@RequiredArgsConstructor
public class MediaObservationService {
    private final ConversationAccess access;
    private final ChatAttachmentRepository attachments;
    private final AttachmentStore store;
    private final MediaObservationRepository observations;
    private final MediaObservationRequestRepository requests;
    private final MediaObservationBodies bodies;
    private final ChatContentMutationCoordinator mutations;
    private final ObjectMapper json;
    private final Clock clock;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<MediaObservationView> list(CurrentUser user, Long conversationId, String afterAssetId, int limit) {
        access.requireOwn(user, conversationId);
        if (limit < 1 || limit > 100) {
            throw invalid();
        }
        List<ChatAttachment> sent = sent(conversationId);
        int start = 0;
        if (afterAssetId != null) {
            Long cursor = cursor(afterAssetId);
            var attachment = attachments.findById(cursor).orElseThrow(MediaObservationService::invalid);
            if (!conversationId.equals(attachment.conversationId())) {
                throw notFound();
            }
            start = ordinal(sent, cursor);
            if (start == 0) {
                throw invalid();
            }
        }
        var result = new ArrayList<MediaObservationView>();
        for (int index = start; index < sent.size() && result.size() < limit; index++) {
            ChatAttachment attachment = sent.get(index);
            int ordinal = index + 1;
            if (!user.id().equals(attachment.uploadedByUserId()) || !readable(user, conversationId, attachment.id())) {
                result.add(blocked(attachment.id(), ordinal));
                continue;
            }
            String fingerprint;
            try {
                fingerprint = fingerprint(attachment);
            } catch (ApiException ex) {
                if (ex.code() != ErrorCode.ATTACHMENT_GONE) {
                    throw ex;
                }
                result.add(blocked(attachment.id(), ordinal));
                continue;
            }
            MediaObservation row = observations
                    .findFirstByAttachmentIdOrderByRevisionDesc(attachment.id())
                    .orElse(null);
            result.add(view(user, attachment, ordinal, fingerprint, row));
        }
        access.requireOwn(user, conversationId);
        var checked = result.stream()
                .map(view -> recheckList(user, conversationId, view))
                .toList();
        access.requireOwn(user, conversationId);
        return checked;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public MediaObservationView record(
            CurrentUser user,
            Long conversationId,
            Long attachmentId,
            long expectedRevision,
            UUID requestId,
            MediaObservationInput input,
            ObservationProvenance source) {
        access.requireOwn(user, conversationId);
        if (expectedRevision < 0 || requestId == null || input == null || attachmentId == null || attachmentId <= 0) {
            throw invalid();
        }
        input.validate(source);
        String plain = json.writeValueAsString(input);
        if (plain.getBytes(StandardCharsets.UTF_8).length > MediaObservationInput.MAX_BODY_BYTES) {
            throw invalid();
        }
        String requestHash = Sha256.hex(json.writeValueAsString(Arrays.asList(
                expectedRevision,
                input,
                source.kind(),
                source.executionId(),
                source.provider(),
                source.providerVersion(),
                source.model(),
                source.modelVersion(),
                source.schemaVersion(),
                source.promptVersion())));
        MediaObservationView result =
                mutations.run(user.id(), new ChatContentMutationTarget(conversationId, List.of(attachmentId)), () -> {
                    ChatAttachment attachment = requireReadable(user, conversationId, attachmentId);
                    String fingerprint = fingerprint(attachment);
                    MediaObservation latest = observations
                            .findFirstByAttachmentIdOrderByRevisionDesc(attachmentId)
                            .orElse(null);
                    long revision = latest == null ? 0 : latest.revision();
                    checkStored(user, latest, fingerprint, revision);
                    var alias = requests.findByAttachmentIdAndRequestId(attachmentId, requestId.toString())
                            .orElse(null);
                    MediaObservation row;
                    if (alias != null) {
                        if (!requestHash.equals(alias.requestHash())) {
                            throw conflict(revision);
                        }
                        row = observations.findById(alias.observationId()).orElseThrow(() -> conflict(revision));
                        checkStored(user, row, fingerprint, revision);
                    } else {
                        if (expectedRevision != revision
                                || latest != null
                                        && latest.provenanceKind() == ObservationProvenanceKind.USER_CORRECTION
                                        && source.kind() == ObservationProvenanceKind.MODEL_RESULT) {
                            throw conflict(revision);
                        }
                        String analysisKey = completed(source.kind(), input.status())
                                ? MediaObservationAnalysisKey.compute(json, fingerprint, source, input.coverage())
                                : null;
                        if (reusable(latest, user.id(), analysisKey)) {
                            row = latest;
                        } else {
                            bodies.requireEncryption();
                            row = observations.saveAndFlush(new MediaObservation(
                                    attachment,
                                    revision + 1,
                                    fingerprint,
                                    input.status(),
                                    source.kind(),
                                    source.schemaVersion(),
                                    source.promptVersion(),
                                    source.executionId(),
                                    source.provider(),
                                    source.providerVersion(),
                                    source.model(),
                                    source.modelVersion(),
                                    analysisKey,
                                    clock.instant().truncatedTo(ChronoUnit.MICROS)));
                            bodies.seal(row, plain);
                        }
                        requests.saveAndFlush(
                                new MediaObservationRequest(attachmentId, requestId, requestHash, row.id()));
                    }
                    return view(user, attachment, ordinal(sent(conversationId), attachmentId), fingerprint, row);
                });
        var attachment = requireReadable(user, conversationId, attachmentId);
        String currentFingerprint = fingerprint(attachment);
        requireReadable(user, conversationId, attachmentId);
        if (result.expiresAt() != null && !result.expiresAt().isAfter(clock.instant())) {
            throw gone();
        }
        if (!currentFingerprint.equals(result.sourceFingerprint())) {
            long revision = observations
                    .findFirstByAttachmentIdOrderByRevisionDesc(attachmentId)
                    .map(MediaObservation::revision)
                    .orElse(0L);
            throw conflict(revision);
        }
        return result;
    }

    private boolean reusable(MediaObservation row, Long owner, String analysisKey) {
        if (analysisKey == null
                || row == null
                || row.bodyKeyId() == null
                || !completed(row.provenanceKind(), row.status())
                || MediaObservationAnalysisKey.hasUnknownIdentity(provenance(row))
                || !analysisKey.equals(row.analysisKey())) {
            return false;
        }
        return openValidatedBody(row, owner)
                .filter(body -> analysisKey.equals(MediaObservationAnalysisKey.compute(
                        json, row.sourceFingerprint(), provenance(row), body.coverage())))
                .isPresent();
    }

    private static boolean completed(ObservationProvenanceKind kind, ObservationStatus status) {
        return kind == ObservationProvenanceKind.MODEL_RESULT
                && (status == ObservationStatus.SUCCEEDED
                        || status == ObservationStatus.PARTIAL
                        || status == ObservationStatus.NEEDS_REVIEW);
    }

    private MediaObservationView recheckList(CurrentUser user, Long conversationId, MediaObservationView view) {
        Long id = Long.valueOf(view.assetId());
        if (view.status() == ObservationStatus.UNAVAILABLE) {
            return view;
        }
        try {
            String currentFingerprint = fingerprint(requireReadable(user, conversationId, id));
            requireReadable(user, conversationId, id);
            if (view.expiresAt() != null && !view.expiresAt().isAfter(clock.instant())) {
                return blocked(id, view.ordinal());
            }
            if (!currentFingerprint.equals(view.sourceFingerprint())) {
                return new MediaObservationView(
                        view.assetId(),
                        view.ordinal(),
                        currentFingerprint,
                        view.revision(),
                        view.revision() == 0 ? ObservationStatus.NOT_ANALYZED : ObservationStatus.NEEDS_REVIEW,
                        null,
                        view.provenance(),
                        view.expiresAt(),
                        view.revision() == 0 ? null : "CONTENT_UNAVAILABLE");
            }
            return view;
        } catch (ApiException ex) {
            if (ex.code() != ErrorCode.ATTACHMENT_GONE && ex.code() != ErrorCode.CONVERSATION_NOT_FOUND) {
                throw ex;
            }
            return blocked(id, view.ordinal());
        }
    }

    private void checkStored(CurrentUser user, MediaObservation row, String fingerprint, long revision) {
        if (row == null) {
            return;
        }
        if (!user.id().equals(row.ownerUserId())) {
            throw notFound();
        }
        if (!row.expiresAt().isAfter(clock.instant())) {
            throw gone();
        }
        if (!fingerprint.equals(row.sourceFingerprint())) {
            throw conflict(revision);
        }
    }

    private MediaObservationView view(
            CurrentUser user, ChatAttachment attachment, int ordinal, String fingerprint, MediaObservation row) {
        if (row == null) {
            return new MediaObservationView(
                    attachment.id().toString(),
                    ordinal,
                    fingerprint,
                    0L,
                    ObservationStatus.NOT_ANALYZED,
                    null,
                    null,
                    attachment.expiresAt(),
                    null);
        }
        if (!user.id().equals(row.ownerUserId()) || !row.expiresAt().isAfter(clock.instant())) {
            return blocked(attachment.id(), ordinal);
        }
        ObservationProvenance source = provenance(row);
        MediaObservationInput body = fingerprint.equals(row.sourceFingerprint())
                ? openValidatedBody(row, user.id()).orElse(null)
                : null;
        String error = body == null ? "CONTENT_UNAVAILABLE" : body.errorCode();
        ObservationStatus status = body == null ? ObservationStatus.NEEDS_REVIEW : row.status();
        if (status == ObservationStatus.PROCESSING
                && !row.createdAt().plus(Duration.ofMinutes(15)).isAfter(clock.instant())) {
            status = ObservationStatus.NEEDS_REVIEW;
            error = "ANALYSIS_STALE";
        }
        return new MediaObservationView(
                attachment.id().toString(),
                ordinal,
                fingerprint,
                row.revision(),
                status,
                body,
                source,
                row.expiresAt(),
                error);
    }

    private static ObservationProvenance provenance(MediaObservation row) {
        return new ObservationProvenance(
                row.provenanceKind(),
                row.originExecutionId(),
                row.provider(),
                row.providerVersion(),
                row.model(),
                row.modelVersion(),
                row.schemaVersion(),
                row.promptVersion(),
                row.createdAt());
    }

    private Optional<MediaObservationInput> openValidatedBody(MediaObservation row, Long owner) {
        var opened = bodies.open(row, owner);
        if (opened.isPresent()
                && opened.get().getBytes(StandardCharsets.UTF_8).length <= MediaObservationInput.MAX_BODY_BYTES) {
            try {
                var body = json.readValue(opened.get(), MediaObservationInput.class);
                if (body != null) {
                    body.validate(provenance(row));
                    if (body.status() == row.status()) {
                        return Optional.of(body);
                    }
                }
            } catch (JacksonException | ApiException ex) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private ChatAttachment requireReadable(CurrentUser user, Long conversationId, Long attachmentId) {
        access.requireOwn(user, conversationId);
        var row = attachments
                .findByIdAndConversationId(attachmentId, conversationId)
                .filter(attachment -> user.id().equals(attachment.uploadedByUserId()))
                .orElseThrow(MediaObservationService::notFound);
        if (!readable(user, conversationId, attachmentId) || !row.expiresAt().isAfter(clock.instant())) {
            throw gone();
        }
        return row;
    }

    private boolean readable(CurrentUser user, Long conversationId, Long attachmentId) {
        return attachments
                .existsByIdAndConversationIdAndUploadedByUserIdAndMessageIdIsNotNullAndDeletedAtIsNullAndExpiresAtAfter(
                        attachmentId, conversationId, user.id(), clock.instant());
    }

    private List<ChatAttachment> sent(Long conversationId) {
        return attachments.findByConversationIdOrderByMessageIdAscPositionAsc(conversationId).stream()
                .filter(row -> row.messageId() != null)
                .toList();
    }

    private static int ordinal(List<ChatAttachment> rows, Long attachmentId) {
        for (int index = 0; index < rows.size(); index++) {
            if (attachmentId.equals(rows.get(index).id())) {
                return index + 1;
            }
        }
        return 0;
    }

    private String fingerprint(ChatAttachment row) {
        MessageDigest digest = Sha256.newDigest();
        try (var input = store.open(row)) {
            byte[] buffer = new byte[8192];
            long size = 0;
            for (int read; (read = input.read(buffer)) != -1; ) {
                size += read;
                if (size > row.byteSize()) {
                    throw gone();
                }
                digest.update(buffer, 0, read);
            }
            if (size == 0 || size != row.byteSize() || !row.expiresAt().isAfter(clock.instant())) {
                throw gone();
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | UncheckedIOException ex) {
            throw gone();
        }
    }

    private static Long cursor(String value) {
        try {
            if (!value.matches("[1-9][0-9]*")) {
                throw invalid();
            }
            return Long.valueOf(value);
        } catch (NumberFormatException ex) {
            throw invalid();
        }
    }

    private static MediaObservationView blocked(Long id, int ordinal) {
        return new MediaObservationView(
                id.toString(), ordinal, null, null, ObservationStatus.UNAVAILABLE, null, null, null, null);
    }

    private static ApiException conflict(long revision) {
        return new ApiException(ErrorCode.MEDIA_OBSERVATION_CONFLICT, "revision=" + revision);
    }

    private static ApiException invalid() {
        return new ApiException(ErrorCode.VALIDATION_FAILED, "invalid media observation");
    }

    private static ApiException notFound() {
        return new ApiException(ErrorCode.CONVERSATION_NOT_FOUND, "this conversation does not exist");
    }

    private static ApiException gone() {
        return new ApiException(ErrorCode.ATTACHMENT_GONE, "this attachment is no longer kept");
    }
}
