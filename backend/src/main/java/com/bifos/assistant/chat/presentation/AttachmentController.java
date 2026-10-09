package com.bifos.assistant.chat.presentation;

import com.bifos.assistant.chat.application.AttachmentContent;
import com.bifos.assistant.chat.application.AttachmentService;
import com.bifos.assistant.chat.application.ConversationAccess;
import com.bifos.assistant.chat.domain.ChatAttachment;
import com.bifos.assistant.chat.presentation.ChatDtos.AttachmentView;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 대화에 올린 사진이다. 첨부에 직접 닿는 경로를 두지 않고 대화 아래에만 둔다. */
@RestController
@RequestMapping("/api/v1/chat/conversations/{conversationId}/attachments")
@RequiredArgsConstructor
public class AttachmentController {

    private final AttachmentService attachments;
    private final CurrentUserProvider currentUser;
    private final ConversationAccess access;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AttachmentView upload(
            @PathVariable UUID conversationId, @RequestParam("file") MultipartFile file) {
        CurrentUser user = currentUser.require();
        ChatAttachment saved = attachments.upload(
                user,
                access.requireOwnId(user, conversationId),
                file.getOriginalFilename(),
                file.getContentType(),
                file.getSize(),
                file);
        // 업로드 트랜잭션이 끝난 뒤에 사본을 만든다.
        attachments.prepareSmall(saved);
        return view(saved);
    }

    @GetMapping("/{attachmentId}")
    public ResponseEntity<InputStreamResource> read(
            @PathVariable UUID conversationId, @PathVariable Long attachmentId) {
        CurrentUser user = currentUser.require();
        AttachmentContent content =
                attachments.read(user, access.requireOwnId(user, conversationId), attachmentId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.contentType()))
                .contentLength(content.byteSize())
                .cacheControl(CacheControl.empty().cachePrivate())
                .body(new InputStreamResource(content.body()));
    }

    @DeleteMapping("/{attachmentId}")
    public void delete(@PathVariable UUID conversationId, @PathVariable Long attachmentId) {
        CurrentUser user = currentUser.require();
        attachments.deleteByUser(user, access.requireOwnId(user, conversationId), attachmentId);
    }

    private static AttachmentView view(ChatAttachment attachment) {
        return AttachmentView.from(attachment);
    }
}
