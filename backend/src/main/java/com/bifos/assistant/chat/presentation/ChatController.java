package com.bifos.assistant.chat.presentation;

import com.bifos.assistant.chat.application.ChatService;
import com.bifos.assistant.chat.application.ActivitySummary;
import com.bifos.assistant.chat.application.ChatTurn;
import com.bifos.assistant.chat.application.ChatEvent;
import com.bifos.assistant.chat.domain.ChatAttachment;
import com.bifos.assistant.chat.domain.ChatMessage;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.presentation.ChatDtos.AttachmentView;
import com.bifos.assistant.chat.presentation.ChatDtos.ConversationView;
import com.bifos.assistant.chat.presentation.ChatDtos.MessageView;
import com.bifos.assistant.chat.presentation.ChatDtos.RenameConversationRequest;
import com.bifos.assistant.chat.presentation.ChatDtos.SendMessageRequest;
import com.bifos.assistant.chat.presentation.ChatDtos.SendMessageResponse;
import com.bifos.assistant.chat.presentation.ChatDtos.StartConversationRequest;
import com.bifos.assistant.chat.presentation.ChatDtos.StartConversationResponse;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.user.infra.AppUserRepository;
import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ChatService chat;
    private final CurrentUserProvider currentUser;
    private final AppUserRepository users;
    private final AgentService agents;

    @PostMapping("/messages")
    public SendMessageResponse send(@Valid @RequestBody SendMessageRequest request) {
        CurrentUser user = currentUser.require();
        ChatTurn turn = chat.send(
                user,
                request.conversationId(),
                request.text(),
                request.agentCode(),
                request.attachmentIds());
        return new SendMessageResponse(turn.conversationId(), turn.executionId(), turn.assistantText());
    }

    @PostMapping(path = "/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@Valid @RequestBody SendMessageRequest request) {
        CurrentUser user = currentUser.require();
        SseEmitter emitter = new SseEmitter(0L);
        AtomicBoolean clientConnected = new AtomicBoolean(true);
        Thread.ofVirtual().name("chat-stream-").start(() -> {
            try {
                chat.stream(
                        user,
                        request.conversationId(),
                        request.text(),
                        request.agentCode(),
                        request.attachmentIds(),
                        event -> send(emitter, event, clientConnected));
            } catch (ApiException ex) {
                send(emitter, ChatEvent.error(ex.code().name(), ex.getMessage()), clientConnected);
            } catch (Exception ex) {
                log.error("chat stream failed", ex);
                send(emitter, ChatEvent.error("INTERNAL_ERROR", "internal error"), clientConnected);
            } finally {
                if (clientConnected.get()) {
                    emitter.complete();
                }
            }
        });
        return emitter;
    }

    private static void send(
            SseEmitter emitter, ChatEvent event, AtomicBoolean clientConnected) {
        if (!clientConnected.get()) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().data(event, MediaType.APPLICATION_JSON));
        } catch (Exception ex) {
            // 브라우저가 끊겨도 Hermes 실행의 최종 상태를 읽고 실행 기록을 남긴다.
            clientConnected.set(false);
            log.debug("could not send a chat event", ex);
        }
    }

    @PostMapping("/conversations")
    public StartConversationResponse start(@RequestBody StartConversationRequest request) {
        return new StartConversationResponse(
                chat.startEmpty(currentUser.require(), request.agentCode()).id());
    }

    @GetMapping("/conversations")
    public List<ConversationView> conversations() {
        return chat.conversationsOf(currentUser.require()).stream()
                .map(this::viewOf)
                .toList();
    }

    @PatchMapping("/conversations/{conversationId}")
    public ConversationView rename(@PathVariable Long conversationId,
            @Valid @RequestBody RenameConversationRequest request) {
        return viewOf(chat.rename(currentUser.require(), conversationId, request.title()));
    }

    @DeleteMapping("/conversations/{conversationId}")
    public ResponseEntity<Void> delete(@PathVariable Long conversationId) {
        chat.delete(currentUser.require(), conversationId);
        return ResponseEntity.noContent().build();
    }

    private ConversationView viewOf(Conversation conversation) {
        Agent agent = agents.requireById(conversation.agentId());
        return new ConversationView(conversation.id(), conversation.title(), agent.code(),
                agent.name(), conversation.updatedAt());
    }

    @GetMapping("/conversations/{conversationId}/messages")
    public List<MessageView> messages(@PathVariable Long conversationId) {
        CurrentUser user = currentUser.require();
        String senderName = users.findById(user.id()).map(it -> it.displayName()).orElse(null);
        List<ChatMessage> history = chat.history(user, conversationId);
        Set<Long> withChildren = chat.executionIdsHavingChildren(history);
        Map<Long, String> switched = chat.switchedLabels(history);
        Map<Long, ActivitySummary> activity = chat.activitySummaries(history);
        Map<Long, List<ChatAttachment>> attached = chat.attachmentsByMessage(user, conversationId);
        return history.stream()
                .map(
                        it ->
                                new MessageView(
                                        it.id(),
                                        it.role().name(),
                                        it.content(),
                                        it.senderUserId() == null ? null : senderName,
                                        it.executionId(),
                                        // 사용자 메시지는 실행 번호가 없다. 빈 번호로 묶음을 묻지 않는다.
                                        it.executionId() != null && withChildren.contains(it.executionId()),
                                        it.executionId() == null ? null : switched.get(it.executionId()),
                                        it.createdAt(),
                                        attached.getOrDefault(it.id(), List.of()).stream()
                                                .map(AttachmentView::from)
                                                .toList(),
                                        it.executionId() == null ? null : activity.get(it.executionId())))
                .toList();
    }
}
