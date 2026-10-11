package com.bifos.assistant.chat.presentation.contentdraft;

import org.springframework.http.converter.HttpMessageNotReadableException;
import com.bifos.assistant.chat.application.ContentDraftException;
import com.bifos.assistant.chat.application.ContentDraftInputReader;
import com.bifos.assistant.chat.application.ContentDraftService;
import com.bifos.assistant.chat.application.ConversationAccess;
import com.bifos.assistant.chat.application.model.ContentDraftView;
import com.bifos.assistant.chat.presentation.contentdraft.ContentDraftDtos.DraftError;
import com.bifos.assistant.chat.presentation.contentdraft.ContentDraftDtos.ListResponse;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.shared.error.ErrorResponse;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** 대화 주인의 공개 UUID 경로로만 원고를 읽고 수정한다. */
@RestController
@RequestMapping("/api/v1/chat/conversations/{conversationId}/content-drafts")
@RequiredArgsConstructor
public class ContentDraftController {
    private static final JsonMapper STRICT = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final CurrentUserProvider currentUser;
    private final ConversationAccess access;
    private final ContentDraftInputReader reader;
    private final ContentDraftService drafts;


    @PostMapping
    public ResponseEntity<ContentDraftView> create(@PathVariable String conversationId, @RequestBody String body) {
        var user = currentUser.require();
        var id = access.requireOwnId(user, ContentDraftInputReader.uuid(conversationId));
        return ResponseEntity.status(201).body(drafts.create(user, id, reader.read(parse(body))));
    }


    @GetMapping
    public ListResponse list(@PathVariable String conversationId) {
        var user = currentUser.require();
        return new ListResponse(drafts.list(user, access.requireOwnId(user, ContentDraftInputReader.uuid(conversationId))));
    }


    @GetMapping("/{draftId}")
    public ContentDraftView read(@PathVariable String conversationId, @PathVariable String draftId) {
        return readRevision(conversationId, draftId, null);
    }


    @GetMapping("/{draftId}/revisions/{revision}")
    public ContentDraftView readRevision(@PathVariable String conversationId, @PathVariable String draftId, @PathVariable String revision) {
        var user = currentUser.require();
        var id = access.requireOwnId(user, ContentDraftInputReader.uuid(conversationId));
        Long number = revision == null ? null : ContentDraftInputReader.revision(parse(revision));
        return drafts.read(user, id, ContentDraftInputReader.uuid(draftId), number);
    }


    @PutMapping("/{draftId}")
    public ContentDraftView revise(@PathVariable String conversationId, @PathVariable String draftId, @RequestBody String body) {
        var user = currentUser.require();
        var id = access.requireOwnId(user, ContentDraftInputReader.uuid(conversationId));
        var node = parse(body);
        ContentDraftInputReader.fields(node, Set.of("expectedRevision", "draft"), Set.of());
        return drafts.revise(user, id, ContentDraftInputReader.uuid(draftId),
            ContentDraftInputReader.revision(node.get("expectedRevision")), reader.read(node.get("draft")));
    }


    @ExceptionHandler(ContentDraftException.class)
    public ResponseEntity<DraftError> rejected(ContentDraftException ex) {
        return ResponseEntity.status(ex.code().status()).body(new DraftError(ex.code().name(), ex.getMessage(), ex.currentRevision()));
    }


    /** 파서 예외 원문과 cause를 응답이나 로그로 보내지 않는다. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> malformed() {
        return ResponseEntity.badRequest().body(new ErrorResponse("VALIDATION_FAILED", "원고 입력 형식이 올바르지 않습니다."));
    }


    private static JsonNode parse(String value) {
        try {
            return STRICT.readTree(value);
        } catch (JacksonException ex) {
            throw ContentDraftInputReader.invalid();
        }
    }
}
