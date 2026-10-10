package com.bifos.assistant.chat.presentation;

import com.bifos.assistant.chat.application.ConversationAccess;
import com.bifos.assistant.chat.application.MediaObservationInputReader;
import com.bifos.assistant.chat.application.MediaObservationPages;
import com.bifos.assistant.chat.application.MediaObservationService;
import com.bifos.assistant.chat.application.model.ObservationProvenance;
import com.bifos.assistant.chat.domain.type.ObservationProvenanceKind;
import com.bifos.assistant.chat.presentation.ChatDtos.MediaObservationCorrectionRequest;
import com.bifos.assistant.chat.presentation.ChatDtos.MediaObservationPageResponse;
import com.bifos.assistant.chat.presentation.ChatDtos.MediaObservationResponse;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.shared.error.ErrorResponse;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** 대화 주인만 관찰을 읽고 사용자 정정을 남긴다. */
@RestController
@RequestMapping("/api/v1/chat/conversations/{conversationId}/media-observations")
@RequiredArgsConstructor
public class MediaObservationController {
    private final CurrentUserProvider currentUser;
    private final ConversationAccess access;
    private final MediaObservationPages pages;
    private final MediaObservationInputReader reader;
    private final MediaObservationService observations;

    @GetMapping
    public MediaObservationPageResponse list(
            @PathVariable UUID conversationId,
            @RequestParam(required = false) String afterAssetId,
            @RequestParam(defaultValue = "30") int limit) {
        var user = currentUser.require();
        return MediaObservationPageResponse.from(
                pages.list(user, access.requireOwnId(user, conversationId), afterAssetId, limit));
    }

    @PutMapping("/{assetId}")
    public MediaObservationResponse correct(
            @PathVariable UUID conversationId, @PathVariable String assetId, @RequestBody JsonNode body) {
        var user = currentUser.require();
        Long id = access.requireOwnId(user, conversationId);
        var request = MediaObservationCorrectionRequest.from(body);
        var source = new ObservationProvenance(
                ObservationProvenanceKind.USER_CORRECTION,
                null,
                null,
                null,
                null,
                null,
                ObservationProvenance.SCHEMA_VERSION,
                ObservationProvenance.PROMPT_VERSION,
                null);
        var input = reader.read(request.observation(), source);
        return MediaObservationResponse.from(observations.record(
                user,
                id,
                MediaObservationInputReader.assetId(assetId),
                request.expectedRevision(),
                request.requestId(),
                input,
                source));
    }

    /** Jackson 오류의 메시지와 cause에는 제출 본문이 포함될 수 있다. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> malformed() {
        return ResponseEntity.badRequest().body(new ErrorResponse("VALIDATION_FAILED", "invalid media observation"));
    }
}
