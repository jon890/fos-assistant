package com.bifos.assistant.chat.presentation.contentsnapshot;

import com.bifos.assistant.chat.application.ContentDraftInputReader;
import com.bifos.assistant.chat.application.ContentDraftJobQueries;
import com.bifos.assistant.chat.application.ContentDraftSnapshots;
import com.bifos.assistant.chat.conversation.application.ConversationAccess;
import com.bifos.assistant.chat.application.model.ContentDraftJobView;
import com.bifos.assistant.chat.application.model.ContentDraftSnapshotView;
import com.bifos.assistant.chat.domain.ContentDraftException;
import com.bifos.assistant.chat.presentation.contentsnapshot.ContentDraftSnapshotDtos.JobPageResponse;
import com.bifos.assistant.chat.presentation.contentsnapshot.ContentDraftSnapshotDtos.SnapshotError;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 원래 대화 주인에게 승인할 snapshot 과 Control Plane 이 보관한 job 상태만 읽어 준다. 외부 저장을 시작하지 않는다. */
@RestController
@RequestMapping("/api/v1/chat/conversations/{conversationId}")
@RequiredArgsConstructor
public class ContentDraftSnapshotController {
    private static final int PAGE = 20;
    private final CurrentUserProvider currentUser;
    private final ConversationAccess access;
    private final ContentDraftSnapshots snapshots;
    private final ContentDraftJobQueries jobs;


    /** 현재 원고·원본·바인딩이 snapshot 과 같을 때만 canonical preview 를 준다. 다르면 본문 없이 409 다. */
    @GetMapping("/content-drafts/{draftId}/snapshots/{snapshotId}")
    public ContentDraftSnapshotView snapshot(@PathVariable String conversationId, @PathVariable String draftId,
        @PathVariable String snapshotId) {
        var user = currentUser.require();
        var id = access.requireOwnId(user, ContentDraftInputReader.uuid(conversationId));
        var view = snapshots.readCurrentSnapshot(user, id, ContentDraftInputReader.uuid(snapshotId));
        if (!view.draftId().equals(ContentDraftInputReader.uuid(draftId))) {
            throw notFound();
        }
        return view;
    }


    @GetMapping("/content-drafts/{draftId}/snapshots/{snapshotId}/job")
    public ContentDraftJobView job(@PathVariable String conversationId, @PathVariable String draftId,
        @PathVariable String snapshotId) {
        var user = currentUser.require();
        var id = access.requireOwnId(user, ContentDraftInputReader.uuid(conversationId));
        return jobs.refresh(user, id, ContentDraftInputReader.uuid(draftId), ContentDraftInputReader.uuid(snapshotId));
    }


    /** 보관한 상태만 읽는다. 원격 상태를 조회하지 않으며 cursor 는 같은 주인·대화의 snapshot 공개 ID 다. */
    @GetMapping("/content-draft-jobs")
    public JobPageResponse list(@PathVariable String conversationId,
        @RequestParam(required = false) String beforeSnapshotId) {
        var user = currentUser.require();
        var id = access.requireOwnId(user, ContentDraftInputReader.uuid(conversationId));
        UUID cursor = beforeSnapshotId == null ? null : ContentDraftInputReader.uuid(beforeSnapshotId);
        var page = jobs.list(user, id, cursor, PAGE);
        return new JobPageResponse(page.items(), page.nextBeforeSnapshotId());
    }


    @ExceptionHandler(ContentDraftException.class)
    public ResponseEntity<SnapshotError> rejected(ContentDraftException ex) {
        return ResponseEntity.status(ex.code().status()).body(new SnapshotError(ex.code().name()));
    }


    private static ApiException notFound() {
        return new ApiException(ErrorCode.CONVERSATION_NOT_FOUND, "대화를 찾을 수 없습니다.");
    }
}
