package com.bifos.assistant.chat.presentation;

import com.bifos.assistant.chat.application.ChatService;
import com.bifos.assistant.chat.application.ActivitySummary;
import com.bifos.assistant.chat.application.ChatTurn;
import com.bifos.assistant.chat.application.ConversationAccess;
import com.bifos.assistant.chat.domain.ChatAttachment;
import com.bifos.assistant.chat.domain.ChatMessage;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.presentation.ChatDtos.AttachmentView;
import com.bifos.assistant.chat.presentation.ChatDtos.ConversationRefView;
import com.bifos.assistant.chat.presentation.ChatDtos.ConversationView;
import com.bifos.assistant.chat.presentation.ChatDtos.MessageView;
import com.bifos.assistant.chat.presentation.ChatDtos.RenameConversationRequest;
import com.bifos.assistant.chat.presentation.ChatDtos.RunningTurnView;
import com.bifos.assistant.chat.presentation.ChatDtos.SendMessageRequest;
import com.bifos.assistant.chat.presentation.ChatDtos.SendMessageResponse;
import com.bifos.assistant.chat.presentation.ChatDtos.StartConversationRequest;
import com.bifos.assistant.chat.presentation.ChatDtos.StartConversationResponse;
import com.bifos.assistant.chat.presentation.ChatDtos.StopResponse;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.user.infra.AppUserRepository;
import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chat;
    private final CurrentUserProvider currentUser;
    private final AppUserRepository users;
    private final AgentService agents;
    private final ConversationAccess access;
    private final ChatEventStreams streams;

    @PostMapping("/messages")
    public SendMessageResponse send(@Valid @RequestBody SendMessageRequest request) {
        CurrentUser user = currentUser.require();
        ChatTurn turn = chat.send(
                user,
                numberOf(user, request.conversationId()),
                request.text(),
                request.agentCode(),
                request.attachmentIds());
        return new SendMessageResponse(turn.conversationPublicId(), turn.executionId(), turn.assistantText());
    }

    @PostMapping("/executions/{executionId}/stop")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public StopResponse stop(@PathVariable Long executionId) {
        chat.stop(currentUser.require(), executionId);
        return new StopResponse("stopping");
    }

    @PostMapping(path = "/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@Valid @RequestBody SendMessageRequest request) {
        CurrentUser user = currentUser.require();
        // 스트림 안에서 바꿔야 없는 대화가 지금처럼 SSE error 사건으로 알려진다.
        return streams.open(event -> chat.stream(
                user,
                numberOf(user, request.conversationId()),
                request.text(),
                request.agentCode(),
                request.attachmentIds(),
                event));
    }

    @PostMapping(path = "/conversations/{conversationId}/regenerate/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter regenerate(@PathVariable UUID conversationId) {
        CurrentUser user = currentUser.require();
        // SSE 를 열기 전에 확인해야 남의 대화에 200 스트림 오류가 아닌 404를 돌려준다.
        Long id = access.requireOwnId(user, conversationId);
        return streams.open(event -> chat.regenerate(user, id, event));
    }

    /** 본문의 공개 식별자를 서비스가 받는 대화 번호로 바꾼다. 비어 있으면 새 대화다. */
    private Long numberOf(CurrentUser user, UUID publicId) {
        return publicId == null ? null : access.requireOwnId(user, publicId);
    }

    @PostMapping("/conversations")
    public StartConversationResponse start(@RequestBody StartConversationRequest request) {
        return new StartConversationResponse(
                chat.startEmpty(currentUser.require(), request.agentCode()).publicId());
    }

    @GetMapping("/conversations")
    public List<ConversationView> conversations() {
        return chat.conversationsOf(currentUser.require()).stream()
                .map(this::viewOf)
                .toList();
    }

    @PatchMapping("/conversations/{conversationId}")
    public ConversationView rename(@PathVariable UUID conversationId,
            @Valid @RequestBody RenameConversationRequest request) {
        CurrentUser user = currentUser.require();
        return viewOf(chat.rename(user, access.requireOwnId(user, conversationId), request.title()));
    }

    @DeleteMapping("/conversations/{conversationId}")
    public ResponseEntity<Void> delete(@PathVariable UUID conversationId) {
        CurrentUser user = currentUser.require();
        chat.delete(user, access.requireOwnId(user, conversationId));
        return ResponseEntity.noContent().build();
    }

    /**
     * 옛 주소의 대화 번호로 공개 식별자를 찾는다.
     *
     * <p>옛 링크를 새 주소로 넘겨 주는 데만 쓴다. 주인이 아니거나 지운 대화면 다른 경로와 같은 응답이다.
     */
    @GetMapping("/conversations/by-number/{number}")
    public ConversationRefView byNumber(@PathVariable Long number) {
        return new ConversationRefView(access.requireOwn(currentUser.require(), number).publicId());
    }

    private ConversationView viewOf(Conversation conversation) {
        Agent agent = agents.requireById(conversation.agentId());
        return new ConversationView(conversation.publicId(), conversation.title(), agent.code(),
                agent.name(), conversation.updatedAt());
    }

    /**
     * 대화에 지금 도는 turn 이 있는지 알려 준다. 같은 대화를 연 다른 창이 주기적으로 부른다.
     *
     * <p>주인 확인을 먼저 해 남의 대화에 도는 turn 이 있는지 새지 않게 한다.
     */
    @GetMapping("/conversations/{conversationId}/running")
    public RunningTurnView running(@PathVariable UUID conversationId) {
        CurrentUser user = currentUser.require();
        return RunningTurnView.from(chat.running(user, access.requireOwnId(user, conversationId)));
    }

    @GetMapping("/conversations/{conversationId}/messages")
    public List<MessageView> messages(@PathVariable UUID conversationId) {
        CurrentUser user = currentUser.require();
        Long number = access.requireOwnId(user, conversationId);
        String senderName = users.findById(user.id()).map(it -> it.displayName()).orElse(null);
        List<ChatMessage> history = chat.history(user, number);
        Set<Long> withChildren = chat.executionIdsHavingChildren(history);
        Map<Long, String> switched = chat.switchedLabels(history);
        Map<Long, ActivitySummary> activity = chat.activitySummaries(history);
        Map<Long, com.bifos.assistant.usage.domain.ExecutionStatus> statuses = chat.statuses(history);
        Map<Long, List<ChatAttachment>> attached = chat.attachmentsByMessage(user, number);
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
                                        it.replacesMessageId(),
                                        it.createdAt(),
                                        attached.getOrDefault(it.id(), List.of()).stream()
                                                .map(AttachmentView::from)
                                                .toList(),
                                        it.executionId() == null ? null : activity.get(it.executionId()),
                                        it.executionId() == null || statuses.get(it.executionId()) == null
                                                ? null : statuses.get(it.executionId()).name()))
                .toList();
    }
}
