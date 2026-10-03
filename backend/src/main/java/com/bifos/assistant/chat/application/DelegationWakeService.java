package com.bifos.assistant.chat.application;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.chat.domain.ChatMessage;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.domain.type.MessageRole;
import com.bifos.assistant.chat.infra.ChatMessageRepository;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

/**
 * 맡긴 일의 결과가 끝나면 부모 대화의 turn 을 자동으로 연다(ADR-040).
 *
 * <p>승인한 동작의 결과처럼 {@link AutoTurnResultSource} 가 낸 결과도 같은 turn 에 모아 전한다(ADR-050).
 *
 * <p>여는지는 그 대화의 turn 잠금을 잡을 수 있는지로 정한다. 잡지 못하면 그 turn 이 닫힐 때 {@link NextTurnDispatcher} 가 다시 부른다. 기다리는
 * 목록은 따로 두지 않는다. 아직 전하지 않은 끝난 위임 실행 줄이 곧 목록이다.
 *
 * <p>사용자 실행 한도에 닿으면 상한이 있는 재시도를 건다(ADR-069). 결과를 잃지 않게 {@link #FAILURE_BACKOFF} 뒤 다시
 * 시도하되, 연속 {@link #MAX_BUSY_RETRIES} 번까지만 예약한다. 그 뒤에는 그 대화의 turn 닫기, 위임 종료, 기동 같은 기존
 * 계기에 맡긴다. 재시도는 {@link WakeRetryDue} 사건으로 내고 {@link NextTurnDispatcher} 가 받는다. 대화를 다시 부르는
 * 자리를 그것 하나로 두기 위해서다.
 *
 * <p>잠금과 재시도 수는 {@link TurnCancellation} 의 메모리 맵처럼 서버 하나를 전제로 한다.
 */
@Service
@Slf4j
public class DelegationWakeService {

    /** 자동 turn 한도에 닿았을 때 대화에 남기는 알림 줄이다. 같은 줄이 연달아 쌓이지 않게 글로 견준다. */
    static final String LIMIT_NOTICE = "자동으로 이어 가는 횟수를 넘었어요. 이어서 하려면 메시지를 보내 주세요";

    /**
     * 결과를 전하기 전에 실패한 자동 turn 뒤에 다시 열지 않고 기다리는 시간이다.
     *
     * <p>결과가 그대로 남으므로, 기다리지 않으면 그 turn 을 닫는 자리에서 같은 결과로 곧바로 다시 열어 같은 실패를 쉬지 않고
     * 되풀이한다. 이 시간이 지난 뒤의 사건, turn 닫기, 기동 훑기가 다시 시도한다.
     */
    static final Duration FAILURE_BACKOFF = Duration.ofSeconds(30);

    /** 사용자 실행 한도로 거절된 자동 turn 을 연달아 다시 시도하는 횟수의 상한이다. */
    static final int MAX_BUSY_RETRIES = 10;

    /** 재시도를 {@link #FAILURE_BACKOFF} 에서 이만큼 더 늦춘다. 실패 시각이 지워지기 전에 사건이 닿지 않게 한다. */
    private static final Duration BUSY_RETRY_MARGIN = Duration.ofSeconds(1);

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
    private final List<AutoTurnResultSource> sources;
    private final TaskScheduler scheduler;
    private final ApplicationEventPublisher events;
    private final Clock clock = Clock.systemUTC();
    /** 결과를 전하기 전에 자동 turn 이 실패한 대화와 그 시각이다. */
    private final Map<Long, Instant> lastFailures = new ConcurrentHashMap<>();
    /** 사용자 실행 한도로 자동 turn 이 연달아 거절된 대화와 그 횟수다. 잠금을 잡으면 지운다. */
    private final Map<Long, Integer> busyRetries = new ConcurrentHashMap<>();

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
            AppUserRepository users,
            List<AutoTurnResultSource> sources,
            TaskScheduler scheduler,
            ApplicationEventPublisher events) {
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
        this.sources = List.copyOf(sources);
        this.scheduler = scheduler;
        this.events = events;
    }

    /** 기동 전에 끝났지만 전하지 못한 결과가 있는 대화를 돌려준다. 이 기능이 꺼져 있으면 비어 있다. */
    public List<Long> conversationsToWake() {
        if (!properties.enabled()) {
            return List.of();
        }
        Set<Long> found = new LinkedHashSet<>(executions.findConversationsWithUndeliveredResults());
        // 기동 정리가 결과를 모르는 것으로 바꾼 승인 줄도 여기서 전해진다.
        sources.forEach(source -> found.addAll(source.conversationsWithUndelivered()));
        return List.copyOf(found);
    }

    /**
     * 그 대화에 전할 결과가 있고 turn 이 돌지 않으면 자동 turn 을 새 가상 스레드에서 연다.
     *
     * <p>흐름 대화, 꺼지거나 지운 에이전트, 지운 대화는 잠금을 잡기 전에 거른다. 잡은 뒤 아무것도 남기지 않고 닫으면
     * 닫기 리스너가 곧바로 다시 불러 끝없이 돈다. 거른 결과는 실행 줄에 그대로 남는다.
     */
    public void tryWake(Long conversationId) {
        if (!properties.enabled() || undeliveredMarks(conversationId).isEmpty()) {
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
        if (agent.isEmpty()
                || agent.get().isDeleted()
                || !agent.get().enabled()
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
        TurnHandle handle;
        try {
            handle = turns.open(owner.id(), conversationId);
        } catch (ApiException ex) {
            if (ex.code() == ErrorCode.CONVERSATION_BUSY) {
                // 도는 turn 이 닫힐 때 다시 확인한다.
                return;
            }
            if (ex.code() == ErrorCode.USER_BUSY) {
                // 결과는 전했다고 적지 않은 채 남는다. 사용자의 다른 대화가 끝나도 이 대화는 다시 불리지 않으므로 직접 예약한다.
                retryLaterAfterUserBusy(conversationId);
                return;
            }
            throw ex;
        }
        busyRetries.remove(conversationId);
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
    private void runAutoTurn(CurrentUser owner, Long conversationId, TurnHandle handle) {
        Set<String> pending = Set.of();
        try {
            pending = undeliveredMarks(conversationId);
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

    /**
     * 사용자 실행 한도로 거절된 대화의 재시도를 예약한다.
     *
     * <p>실패 시각을 적어 그 사이 다른 계기가 곧바로 다시 열지 않게 한다. 연속 거절이 {@link #MAX_BUSY_RETRIES} 를 넘으면
     * 예약하지 않고 센 횟수를 지운다. 그 뒤 다른 계기로 다시 거절되면 새로 10번까지 예약한다. 예약하지 못해도 예외를 올리지 않는다. 기존 계기가 다시 시도한다.
     */
    private void retryLaterAfterUserBusy(Long conversationId) {
        Instant now = Instant.now(clock);
        lastFailures.put(conversationId, now);
        int retries = busyRetries.merge(conversationId, 1, Integer::sum);
        if (retries > MAX_BUSY_RETRIES) {
            // 센 횟수를 지운다. 남겨 두면 나중에 다른 계기로 다시 거절될 때 재시도를 한 번도 걸지 못한다.
            busyRetries.remove(conversationId);
            log.info("사용자 실행 한도로 자동 turn 이 연달아 거절돼 더 예약하지 않는다 conversationId={} retries={}", conversationId, retries);
            return;
        }
        try {
            scheduler.schedule(
                    () -> dueAfterUserBusy(conversationId, now),
                    now.plus(FAILURE_BACKOFF).plus(BUSY_RETRY_MARGIN));
        } catch (RuntimeException ex) {
            log.warn("사용자 실행 한도로 미룬 자동 turn 을 예약하지 못했다 conversationId={}", conversationId, ex);
        }
    }

    /**
     * 예약한 재시도의 때가 됐다. 새 가상 스레드에서 실패 시각을 지우고 사건을 낸다.
     *
     * <p>사건을 받는 쪽이 같은 스레드에서 DB 를 읽고 turn 을 연다. 스케줄러 스레드에서 그대로 돌리면 그동안 다른 예약
     * 작업이 밀린다.
     *
     * @param scheduledAt 예약하며 적은 실패 시각
     */
    private void dueAfterUserBusy(Long conversationId, Instant scheduledAt) {
        try {
            Thread.ofVirtual()
                    .name("delegation-wake-retry-" + conversationId)
                    .start(() -> retryAfterUserBusy(conversationId, scheduledAt));
        } catch (RuntimeException | Error ex) {
            log.warn("사용자 실행 한도로 미룬 자동 turn 의 재시도 스레드를 띄우지 못했다 conversationId={}", conversationId, ex);
        }
    }

    /**
     * 예약하며 적은 실패 시각을 지운 뒤 사건을 낸다.
     *
     * <p>지우지 않으면 {@link #inFailureBackoff} 가 실제 시계로 다시 세어 재시도가 막힐 수 있다. 그 사이 다른 실패가 새
     * 시각을 적었으면 지우지 않는다. 그 실패의 유예를 지킨다.
     */
    private void retryAfterUserBusy(Long conversationId, Instant scheduledAt) {
        lastFailures.remove(conversationId, scheduledAt);
        try {
            events.publishEvent(new WakeRetryDue(conversationId));
        } catch (RuntimeException ex) {
            log.warn("사용자 실행 한도로 미룬 자동 turn 을 다시 시도하지 못했다 conversationId={}", conversationId, ex);
        }
    }

    /** 이 turn 이 전하려던 결과가 아직 남아 있으면 실패 시각을 적는다. 전한 뒤의 실패는 같은 결과로 다시 열지 않으므로 적지 않는다. */
    private void rememberFailureIfUndelivered(Long conversationId, Set<String> pending) {
        try {
            boolean stillUndelivered = undeliveredMarks(conversationId).stream().anyMatch(pending::contains);
            if (stillUndelivered) {
                lastFailures.put(conversationId, Instant.now(clock));
            }
        } catch (RuntimeException ex) {
            // 남았는지 모르면 남은 것으로 본다. 쉬지 않고 다시 여는 것보다 잠시 늦게 전하는 편이 낫다.
            log.warn("자동 turn 이 전하려던 결과가 남았는지 읽지 못했다 conversationId={}", conversationId, ex);
            lastFailures.put(conversationId, Instant.now(clock));
        }
    }

    /**
     * 그 대화에 아직 전하지 않은 결과들의 표식이다. 위임 결과와 다른 쪽이 낸 결과를 함께 본다.
     *
     * <p>표식은 이 클래스 안에서 같은 결과인지 견주는 데만 쓴다.
     */
    private Set<String> undeliveredMarks(Long conversationId) {
        Set<String> marks = new LinkedHashSet<>();
        executions.findUndeliveredResults(conversationId).forEach(result -> marks.add("execution:" + result.id()));
        for (int index = 0; index < sources.size(); index++) {
            String prefix = "source" + index + ":";
            sources.get(index).undelivered(conversationId).forEach(result -> marks.add(prefix + result.key()));
        }
        return marks;
    }

    private boolean inFailureBackoff(Long conversationId) {
        Instant failedAt = lastFailures.get(conversationId);
        if (failedAt == null) {
            return false;
        }
        if (failedAt.plus(FAILURE_BACKOFF).isAfter(Instant.now(clock))) {
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
        ChatMessage saved = messages.save(ChatMessage.fromSystem(conversation.id(), LIMIT_NOTICE, clock.instant()));
        hub.publish(conversation.id(), ChatEvent.system(conversation.publicId(), saved.id(), LIMIT_NOTICE));
    }
}
