package com.bifos.assistant.chat.presentation;

import com.bifos.assistant.chat.application.AttachmentContent;
import com.bifos.assistant.chat.application.AttachmentService;
import com.bifos.assistant.chat.domain.ChatAttachment;
import com.bifos.assistant.chat.presentation.ChatDtos.AttachmentView;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
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

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AttachmentView upload(
            @PathVariable Long conversationId, @RequestParam("file") MultipartFile file) {
        CurrentUser user = currentUser.require();
        ChatAttachment saved = attachments.upload(
                user,
                conversationId,
                file.getOriginalFilename(),
                file.getContentType(),
                file.getSize(),
                file);
        return view(saved);
    }

    @GetMapping("/{attachmentId}")
    public ResponseEntity<InputStreamResource> read(
            @PathVariable Long conversationId, @PathVariable Long attachmentId) {
        AttachmentContent content =
                attachments.read(currentUser.require(), conversationId, attachmentId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.contentType()))
                .contentLength(content.byteSize())
                .cacheControl(CacheControl.empty().cachePrivate())
                .body(new InputStreamResource(content.body()));
    }

    @DeleteMapping("/{attachmentId}")
    public void delete(@PathVariable Long conversationId, @PathVariable Long attachmentId) {
        attachments.deleteByUser(currentUser.require(), conversationId, attachmentId);
    }

    private static AttachmentView view(ChatAttachment attachment) {
        return AttachmentView.from(attachment);
    }
}
