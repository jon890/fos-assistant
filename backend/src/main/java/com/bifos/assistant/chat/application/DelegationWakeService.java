package com.bifos.assistant.chat.application;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.chat.domain.ChatMessage;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.domain.MessageRole;
import com.bifos.assistant.chat.infra.ChatMessageRepository;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.orchestration.application.DelegationFinished;
import com.bifos.assistant.orchestration.application.FlowRegistry;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

/**
 * 맡긴 일의 결과가 끝나면 부모 대화의 turn 을 자동으로 연다(ADR-040).
 *
 * <p>여는지는 그 대화의 turn 잠금을 잡을 수 있는지로 정한다. 잡지 못하면 그 turn 이 닫힐 때 다시 확인한다. 기다리는
 * 목록은 따로 두지 않는다. 아직 전하지 않은 끝난 위임 실행 줄이 곧 목록이다.
 *
 * <p>잠금은 {@link TurnCancellation} 의 메모리 맵이라 서버 하나를 전제로 한다.
 */
@Service
public class DelegationWakeService {

    private static final Logger log = LoggerFactory.getLogger(DelegationWakeService.class);

    /** 자동 turn 한도에 닿았을 때 대화에 남기는 알림 줄이다. 같은 줄이 연달아 쌓이지 않게 글로 견준다. */
    static final String LIMIT_NOTICE = "자동으로 이어 가는 횟수를 넘었어요. 이어서 하려면 메시지를 보내 주세요";

    /**
     * 결과를 전하기 전에 실패한 자동 turn 뒤에 다시 열지 않고 기다리는 시간이다.
     *
     * <p>결과가 그대로 남으므로, 기다리지 않으면 그 turn 을 닫는 자리에서 같은 결과로 곧바로 다시 열어 같은 실패를 쉬지 않고
     * 되풀이한다. 이 시간이 지난 뒤의 사건, turn 닫기, 기동 훑기가 다시 시도한다.
     */
    static final Duration FAILURE_BACKOFF = Duration.ofSeconds(30);

    private final DelegationWakeProperties properties;
    private final TurnCancellation turns;
    private final ChatService chat;
    private final ConversationEventHub hub;
    private final AgentExecutionRepository executions;
    private final ConversationRepository conversations;
    private final ChatMessageRepository messages;
    private final AgentService agents;
    private final FlowRegistry flows;
    private final AppUserRepository users;
    /** 결과를 전하기 전에 자동 turn 이 실패한 대화와 그 시각이다. */
    private final Map<Long, Instant> lastFailures = new ConcurrentHashMap<>();

    public DelegationWakeService(
            DelegationWakeProperties properties,
            TurnCancellation turns,
            ChatService chat,
            ConversationEventHub hub,
            AgentExecutionRepository executions,
            ConversationRepository conversations,
            ChatMessageRepository messages,
            AgentService agents,
            FlowRegistry flows,
            AppUserRepository users) {
        this.properties = properties;
        this.turns = turns;
        this.chat = chat;
        this.hub = hub;
        this.executions = executions;
        this.conversations = conversations;
        this.messages = messages;
        this.agents = agents;
        this.flows = flows;
        this.users = users;
        if (properties.enabled()) {
            // turn 이 도는 동안 끝난 결과는 그 turn 이 닫힐 때 전한다.
            turns.addCloseListener(this::tryWake);
        }
    }

    @EventListener
    public void onDelegationFinished(DelegationFinished event) {
        if (event.conversationId() != null) {
            tryWake(event.conversationId());
        }
    }

    /**
     * 기동 전에 끝났지만 전하지 못한 결과가 있는 대화를 차례로 깨운다.
     *
     * <p>기동 정리가 끊긴 위임 실행을 FAILED 로 적은 뒤에 돈다. 그래야 그 결과도 함께 전한다.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(10)
    public void wakeAfterStartup() {
        if (!properties.enabled()) {
            return;
        }
        for (Long conversationId : executions.findConversationsWithUndeliveredResults()) {
            try {
                tryWake(conversationId);
            } catch (RuntimeException ex) {
                // 한 대화가 실패해도 나머지 대화는 깨운다.
                log.warn("기동 뒤 대화를 깨우지 못했다 conversationId={}", conversationId, ex);
            }
        }
    }

    /**
     * 그 대화에 전할 결과가 있고 turn 이 돌지 않으면 자동 turn 을 새 가상 스레드에서 연다.
     *
     * <p>흐름 대화, 꺼지거나 지운 에이전트, 지운 대화는 잠금을 잡기 전에 거른다. 잡은 뒤 아무것도 남기지 않고 닫으면
     * 닫기 리스너가 곧바로 다시 불러 끝없이 돈다. 거른 결과는 실행 줄에 그대로 남는다.
     */
    public void tryWake(Long conversationId) {
        if (!properties.enabled() || executions.findUndeliveredResults(conversationId).isEmpty()) {
            return;
        }
        if (inFailureBackoff(conversationId)) {
            return;
        }
        Optional<Conversation> found = conversations.findById(conversationId);
        if (found.isEmpty() || found.get().deletedAt() != null) {
            return;
        }
        Conversation conversation = found.get();
        Optional<Agent> agent = agents.findById(conversation.agentId());
        if (agent.isEmpty() || agent.get().isDeleted() || !agent.get().enabled()
                || flows.find(agent.get().flow()) != null) {
            return;
        }
        Optional<AppUser> user = users.findById(conversation.userId());
        if (user.isEmpty()) {
            log.warn("대화 주인이 없어 맡긴 일의 결과를 전하지 않는다 conversationId={}", conversationId);
            return;
        }
        if (conversation.autoTurnCount() >= properties.maxAutoTurns()) {
            noticeLimitReached(conversation);
            return;
        }
        AppUser owner = user.get();
        TurnCancellation.TurnHandle handle;
        try {
            handle = turns.open(owner.id(), conversationId);
        } catch (ApiException ex) {
            if (ex.code() == ErrorCode.CONVERSATION_BUSY) {
                // 도는 turn 이 닫힐 때 다시 확인한다.
                return;
            }
            throw ex;
        }
        CurrentUser current =
                new CurrentUser(owner.id(), owner.email(), owner.displayName(), owner.groupId(), owner.role());
        try {
            Thread.ofVirtual()
                    .name("delegation-wake-" + conversationId)
                    .start(() -> runAutoTurn(current, conversationId, handle));
        } catch (RuntimeException | Error ex) {
            log.warn("자동 turn 스레드를 띄우지 못했다 conversationId={}", conversationId, ex);
            turns.close(handle);
        }
    }

    /**
     * 자동 turn 을 돌리고 잠금을 푼다. 푸는 자리에서 닫기 리스너가 그 사이 쌓인 결과를 이어서 연다.
     *
     * <p>결과를 전했다고 적기 전에 실패하면 잠금을 풀기 전에 실패 시각을 적는다. 그래야 닫기 리스너가 같은 결과로 곧바로
     * 다시 열지 않는다.
     */
    private void runAutoTurn(CurrentUser owner, Long conversationId, TurnCancellation.TurnHandle handle) {
        List<Long> pending = List.of();
        try {
            pending = executions.findUndeliveredResults(conversationId).stream()
                    .map(AgentExecution::id)
                    .toList();
            chat.runDelegationResults(owner, conversationId, handle, event -> hub.publish(conversationId, event));
            lastFailures.remove(conversationId);
        } catch (ApiException ex) {
            log.warn("자동 turn 이 실패했다 conversationId={} code={}", conversationId, ex.code(), ex);
            rememberFailureIfUndelivered(conversationId, pending);
            hub.publish(conversationId, ChatEvent.error(ex.code().name(), ex.getMessage()));
        } catch (RuntimeException ex) {
            log.error("자동 turn 이 예외로 끝났다 conversationId={}", conversationId, ex);
            rememberFailureIfUndelivered(conversationId, pending);
            hub.publish(conversationId, ChatEvent.error("INTERNAL_ERROR", "internal error"));
        } finally {
            turns.close(handle);
        }
    }

    /** 이 turn 이 전하려던 결과가 아직 남아 있으면 실패 시각을 적는다. 전한 뒤의 실패는 같은 결과로 다시 열지 않으므로 적지 않는다. */
    private void rememberFailureIfUndelivered(Long conversationId, List<Long> pending) {
        try {
            boolean stillUndelivered = executions.findUndeliveredResults(conversationId).stream()
                    .anyMatch(result -> pending.contains(result.id()));
            if (stillUndelivered) {
                lastFailures.put(conversationId, Instant.now());
            }
        } catch (RuntimeException ex) {
            // 남았는지 모르면 남은 것으로 본다. 쉬지 않고 다시 여는 것보다 잠시 늦게 전하는 편이 낫다.
            log.warn("자동 turn 이 전하려던 결과가 남았는지 읽지 못했다 conversationId={}", conversationId, ex);
            lastFailures.put(conversationId, Instant.now());
        }
    }

    private boolean inFailureBackoff(Long conversationId) {
        Instant failedAt = lastFailures.get(conversationId);
        if (failedAt == null) {
            return false;
        }
        if (failedAt.plus(FAILURE_BACKOFF).isAfter(Instant.now())) {
            return true;
        }
        lastFailures.remove(conversationId, failedAt);
        return false;
    }

    /**
     * 한도에 닿았다는 알림 줄을 남긴다. 마지막 줄이 이미 그 알림이면 남기지 않는다.
     *
     * <p>잠금 없이 저장하므로 두 사건이 겹치면 두 줄이 생길 수 있다. 알림 한 줄이 더 남는 것뿐이라 잠그지 않는다.
     */
    private void noticeLimitReached(Conversation conversation) {
        List<ChatMessage> history = messages.findByConversationIdOrderByIdAsc(conversation.id());
        if (!history.isEmpty()) {
            ChatMessage last = history.getLast();
            if (last.role() == MessageRole.SYSTEM && LIMIT_NOTICE.equals(last.content())) {
                return;
            }
        }
        ChatMessage saved = messages.save(ChatMessage.fromSystem(conversation.id(), LIMIT_NOTICE));
        hub.publish(conversation.id(), ChatEvent.system(conversation.publicId(), saved.id(), LIMIT_NOTICE));
    }
}
