package com.bifos.assistant.chat.presentation;

import com.bifos.assistant.chat.application.ConversationAccess;
import com.bifos.assistant.chat.application.PendingMessageService;
import com.bifos.assistant.chat.presentation.ChatDtos.PendingMessageRequest;
import com.bifos.assistant.chat.presentation.ChatDtos.PendingQueueView;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** turn 이 도는 동안 보낸 글의 대기 줄을 읽고, 더하고, 취소하고, 멈춘 줄을 푸는 경로다(ADR-047). */
@RestController
@RequestMapping("/api/v1/chat/conversations/{conversationId}/pending")
@RequiredArgsConstructor
public class PendingMessageController {

    private final PendingMessageService pending;
    private final CurrentUserProvider currentUser;
    private final ConversationAccess access;

    @GetMapping
    public PendingQueueView queue(@PathVariable UUID conversationId) {
        CurrentUser user = currentUser.require();
        return PendingQueueView.from(pending.queue(user, access.requireOwnId(user, conversationId)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PendingQueueView enqueue(
            @PathVariable UUID conversationId, @Valid @RequestBody PendingMessageRequest request) {
        CurrentUser user = currentUser.require();
        return PendingQueueView.from(
                pending.enqueue(user, access.requireOwnId(user, conversationId), request.text()));
    }

    @DeleteMapping("/{pendingId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@PathVariable UUID conversationId, @PathVariable Long pendingId) {
        CurrentUser user = currentUser.require();
        pending.cancel(user, access.requireOwnId(user, conversationId), pendingId);
    }

    @PostMapping("/send")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public PendingQueueView send(@PathVariable UUID conversationId) {
        CurrentUser user = currentUser.require();
        return PendingQueueView.from(pending.release(user, access.requireOwnId(user, conversationId)));
    }
}
