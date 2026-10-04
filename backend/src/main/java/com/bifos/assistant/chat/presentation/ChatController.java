package com.bifos.assistant.chat.presentation;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.chat.application.ActivitySummary;
import com.bifos.assistant.chat.application.ChatService;
import com.bifos.assistant.chat.application.ChatTurn;
import com.bifos.assistant.chat.application.ConversationAccess;
import com.bifos.assistant.chat.application.ConversationPage;
import com.bifos.assistant.chat.application.ModelOptionsService;
import com.bifos.assistant.chat.application.ModelTierOptions;
import com.bifos.assistant.chat.application.ModelTierService;
import com.bifos.assistant.chat.domain.ChatArtifact;
import com.bifos.assistant.chat.domain.ChatAttachment;
import com.bifos.assistant.chat.domain.ChatMessage;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.presentation.ChatDtos.ArtifactView;
import com.bifos.assistant.chat.presentation.ChatDtos.AttachmentView;
import com.bifos.assistant.chat.presentation.ChatDtos.ChooseModelRequest;
import com.bifos.assistant.chat.presentation.ChatDtos.ChooseModelTierRequest;
import com.bifos.assistant.chat.presentation.ChatDtos.ConversationPageView;
import com.bifos.assistant.chat.presentation.ChatDtos.ConversationRefView;
import com.bifos.assistant.chat.presentation.ChatDtos.ConversationView;
import com.bifos.assistant.chat.presentation.ChatDtos.MessageView;
import com.bifos.assistant.chat.presentation.ChatDtos.ModelOptionsView;
import com.bifos.assistant.chat.presentation.ChatDtos.RenameConversationRequest;
import com.bifos.assistant.chat.presentation.ChatDtos.RunningTurnView;
import com.bifos.assistant.chat.presentation.ChatDtos.SendMessageRequest;
import com.bifos.assistant.chat.presentation.ChatDtos.SendMessageResponse;
import com.bifos.assistant.chat.presentation.ChatDtos.StartConversationRequest;
import com.bifos.assistant.chat.presentation.ChatDtos.StartConversationResponse;
import com.bifos.assistant.chat.presentation.ChatDtos.StopResponse;
import com.bifos.assistant.chat.presentation.ChatDtos.UpdateDefaultModelTierRequest;
import com.bifos.assistant.chat.presentation.ChatDtos.UpdateGroupModelTiersRequest;
import com.bifos.assistant.model.domain.ModelChoice;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.usage.domain.type.ExecutionStatus;
import com.bifos.assistant.user.application.UserDisplayNameService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/chat")
public class ChatController {

    private final ChatService chat;
    private final CurrentUserProvider currentUser;
    private final UserDisplayNameService userNames;
    private final AgentService agents;
    private final ConversationAccess access;
    private final ChatEventStreams streams;
    private final ModelOptionsService modelOptions;
    private final ModelTierService modelTiers;

    @Autowired
    public ChatController(
            ChatService chat,
            CurrentUserProvider currentUser,
            UserDisplayNameService userNames,
            AgentService agents,
            ConversationAccess access,
            ChatEventStreams streams,
            ModelOptionsService modelOptions,
            ModelTierService modelTiers) {
        this.chat = chat;
        this.currentUser = currentUser;
        this.userNames = userNames;
        this.agents = agents;
        this.access = access;
        this.streams = streams;
        this.modelOptions = modelOptions;
        this.modelTiers = modelTiers;
    }

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
        // 도구의 명령 원문과 내부 값은 보내기 직전에 보는 사람에 맞춰 뺀다. 근거는 ADR-038 과 ADR-063 에 있다.
        return streams.open(send -> chat.stream(
                user,
                numberOf(user, request.conversationId()),
                request.text(),
                request.agentCode(),
                request.attachmentIds(),
                event -> event.forViewer(user).ifPresent(send)));
    }

    @PostMapping(
            path = "/conversations/{conversationId}/regenerate/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter regenerate(@PathVariable UUID conversationId) {
        CurrentUser user = currentUser.require();
        // SSE 를 열기 전에 확인해야 남의 대화에 200 스트림 오류가 아닌 404를 돌려준다.
        Long id = access.requireOwnId(user, conversationId);
        return streams.open(
                send -> chat.regenerate(user, id, event -> event.forViewer(user).ifPresent(send)));
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

    /** 대화 목록을 최근에 바뀐 것부터 한 쪽 돌려준다. 다음 쪽은 {@code nextCursor} 를 {@code cursor} 로 넘겨 읽는다. */
    @GetMapping("/conversations")
    public ConversationPageView conversations(
            @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "30") int limit) {
        ConversationPage page = chat.conversationsOf(currentUser.require(), cursor, limit);
        // 목록은 에이전트를 줄마다 읽지 않고 한 번에 읽는다. 행이 없는 줄은 에이전트 칸만 비운다.
        Map<Long, Agent> byId =
                agents.byIds(page.items().stream().map(Conversation::agentId).toList());
        return new ConversationPageView(
                page.items().stream()
                        .map(conversation -> viewOf(conversation, byId.get(conversation.agentId())))
                        .toList(),
                page.nextCursor());
    }

    /** 대화 한 줄을 읽는다. 목록의 첫 쪽에 없는 오래된 대화를 열 때 쓴다. */
    @GetMapping("/conversations/{conversationId}")
    public ConversationView conversation(@PathVariable UUID conversationId) {
        Conversation found = chat.conversationOf(currentUser.require(), conversationId);
        return viewOf(found, agents.findById(found.agentId()).orElse(null));
    }

    @PatchMapping("/conversations/{conversationId}")
    public ConversationView rename(
            @PathVariable UUID conversationId, @Valid @RequestBody RenameConversationRequest request) {
        CurrentUser user = currentUser.require();
        Conversation renamed = chat.rename(user, access.requireOwnId(user, conversationId), request.title());
        return viewOf(renamed, agents.findById(renamed.agentId()).orElse(null));
    }

    /** 대화에서 쓸 모델과 effort 를 바꾸고 바뀐 대화 한 줄을 돌려준다. */
    @PutMapping("/conversations/{conversationId}/model")
    public ConversationView chooseModel(@PathVariable UUID conversationId, @RequestBody ChooseModelRequest request) {
        CurrentUser user = currentUser.require();
        Conversation chosen = chat.chooseModel(user, access.requireOwnId(user, conversationId), request.toChoice());
        return viewOf(chosen, agents.findById(chosen.agentId()).orElse(null));
    }

    /**
     * 그 에이전트의 profile 로 고를 수 있는 모델과 기본값을 돌려준다.
     *
     * <p>{@code agentCode} 를 필수 인자로 두지 않는다. 빠지면 필수 인자 예외가 받는 곳 없이 500 이 되므로,
     * 서비스의 에이전트 확인이 {@code AGENT_NOT_FOUND} 로 거절하게 둔다.
     */
    @GetMapping("/model-options")
    public ModelOptionsView modelOptions(@RequestParam(required = false) String agentCode) {
        return ModelOptionsView.from(modelOptions.optionsFor(currentUser.require(), agentCode));
    }

    @GetMapping("/model-tiers")
    public ModelTierOptions modelTiers(@RequestParam String agentCode) {
        CurrentUser user = currentUser.require();
        return modelTiers.optionsFor(user, agents.requireStartable(user, agentCode));
    }

    @PutMapping("/model-tiers/default")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateModelTierDefault(@RequestBody UpdateDefaultModelTierRequest request) {
        modelTiers.saveUserDefault(currentUser.require(), request.tier());
    }

    @PutMapping("/model-tiers/group")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateGroupModelTiers(@Valid @RequestBody UpdateGroupModelTiersRequest request) {
        CurrentUser user = currentUser.require();
        modelTiers.saveGroup(
                user,
                request.tiers().stream()
                        .map(tier -> new ModelTierOptions.Tier(
                                tier.tier(), null, tier.provider(), tier.model(), tier.reasoningEffort()))
                        .toList(),
                request.defaultTier());
    }

    @PutMapping("/conversations/{conversationId}/model-tier")
    public ConversationView chooseModelTier(
            @PathVariable UUID conversationId, @Valid @RequestBody ChooseModelTierRequest request) {
        CurrentUser user = currentUser.require();
        Conversation chosen =
                chat.chooseModelTier(user, access.requireOwnId(user, conversationId), request.mode(), request.tier());
        return viewOf(chosen, agents.findById(chosen.agentId()).orElse(null));
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
        return new ConversationRefView(
                access.requireOwn(currentUser.require(), number).publicId());
    }

    /** 에이전트 행이 없으면({@code agent} 가 null) 에이전트 코드와 이름만 비운 줄을 돌려준다. */
    private ConversationView viewOf(Conversation conversation, Agent agent) {
        ModelChoice choice = conversation.modelChoice();
        return new ConversationView(
                conversation.publicId(),
                conversation.title(),
                agent == null ? null : agent.code(),
                agent == null ? null : agent.name(),
                conversation.updatedAt(),
                choice.provider(),
                choice.model(),
                choice.reasoningEffort(),
                conversation.modelSelectionMode(),
                conversation.modelTier(),
                conversation.purpose());
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
        String senderName = userNames.find(user.id());
        List<ChatMessage> history = chat.history(user, number);
        Set<Long> withChildren = chat.executionIdsHavingChildren(history);
        Map<Long, String> switched = chat.switchedLabels(user, history);
        Map<Long, ActivitySummary> activity = chat.activitySummaries(history);
        Map<Long, ExecutionStatus> statuses = chat.statuses(history);
        Map<Long, List<ChatAttachment>> attached = chat.attachmentsByMessage(user, number);
        Map<Long, List<ChatArtifact>> produced = chat.artifactsByMessage(history);
        return history.stream()
                .map(it -> new MessageView(
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
                        produced.getOrDefault(it.id(), List.of()).stream()
                                .map(ArtifactView::from)
                                .toList(),
                        it.executionId() == null ? null : activity.get(it.executionId()),
                        it.executionId() == null || statuses.get(it.executionId()) == null
                                ? null
                                : statuses.get(it.executionId()).name()))
                .toList();
    }
}
