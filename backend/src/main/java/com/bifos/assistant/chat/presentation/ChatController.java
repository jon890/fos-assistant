package com.bifos.assistant.chat.presentation;

import com.bifos.assistant.chat.application.ChatService;
import com.bifos.assistant.chat.application.ChatTurn;
import com.bifos.assistant.chat.presentation.ChatDtos.ConversationView;
import com.bifos.assistant.chat.presentation.ChatDtos.MessageView;
import com.bifos.assistant.chat.presentation.ChatDtos.SendMessageRequest;
import com.bifos.assistant.chat.presentation.ChatDtos.SendMessageResponse;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.user.infra.AppUserRepository;
import com.bifos.assistant.workspace.application.WorkspaceService;
import com.bifos.assistant.workspace.domain.Workspace;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/chat")
public class ChatController {

    private final ChatService chat;
    private final CurrentUserProvider currentUser;
    private final WorkspaceService workspaces;
    private final AppUserRepository users;

    public ChatController(
            ChatService chat,
            CurrentUserProvider currentUser,
            WorkspaceService workspaces,
            AppUserRepository users) {
        this.chat = chat;
        this.currentUser = currentUser;
        this.workspaces = workspaces;
        this.users = users;
    }

    @PostMapping("/messages")
    public SendMessageResponse send(@Valid @RequestBody SendMessageRequest request) {
        CurrentUser user = currentUser.require();
        ChatTurn turn =
                chat.send(user, request.conversationId(), request.text(), request.workspaceCode());
        return new SendMessageResponse(turn.conversationId(), turn.executionId(), turn.assistantText());
    }

    @GetMapping("/conversations")
    public List<ConversationView> conversations() {
        return chat.conversationsOf(currentUser.require()).stream()
                .map(
                        it -> {
                            Workspace workspace = workspaces.findByIdOrNull(it.workspaceId());
                            return new ConversationView(
                                    it.id(), it.title(), workspace == null ? null : workspace.code(), it.updatedAt());
                        })
                .toList();
    }

    @GetMapping("/conversations/{conversationId}/messages")
    public List<MessageView> messages(@PathVariable Long conversationId) {
        CurrentUser user = currentUser.require();
        String senderName = users.findById(user.id()).map(it -> it.displayName()).orElse(null);
        return chat.history(user, conversationId).stream()
                .map(
                        it ->
                                new MessageView(
                                        it.id(),
                                        it.role().name(),
                                        it.content(),
                                        it.senderUserId() == null ? null : senderName,
                                        it.executionId(),
                                        it.createdAt()))
                .toList();
    }
}
