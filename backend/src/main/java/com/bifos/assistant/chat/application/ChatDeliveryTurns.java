package com.bifos.assistant.chat.application;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.chat.application.model.AutoTurnDelivery;
import com.bifos.assistant.chat.application.model.AutoTurnResult;
import com.bifos.assistant.chat.application.model.DeliveryInput;
import com.bifos.assistant.chat.application.model.DeliveryItemRef;
import com.bifos.assistant.chat.application.model.DeliveryState;
import com.bifos.assistant.chat.application.model.ResultDeliveryStart;
import com.bifos.assistant.chat.domain.ChatMessage;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.domain.type.DeliveryAttemptStatus;
import com.bifos.assistant.chat.infra.ChatMessageRepository;
import com.bifos.assistant.context.ContextProperties;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.application.ExecutionDeliveryWriter;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** 저장된 결과를 자동 turn 이나 다시 전달 turn 으로 전한다. */
@Component
@Slf4j
@RequiredArgsConstructor
class ChatDeliveryTurns {
    private final ConversationWriter conversationWriter;
    private final ConversationAccess access;
    private final ChatMessageRepository messages;
    private final AgentService agents;
    private final AgentExecutionRepository executionRepository;
    private final ExecutionDeliveryWriter deliveryWriter;
    private final FlowRegistry flows;
    private final TurnCancellation turns;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final List<AutoTurnResultSource> resultSources;
    private final ResultDeliveryRecorder resultDeliveries;
    private final ContextProperties contextProperties;
    private final ChatTurnRunner chatTurnRunner;
    private final ChatTurnRouting chatTurnRouting;
    private final ChatDeliveryInput chatDeliveryInput;

    /**
     * 아직 전하지 않은 끝난 위임 결과와 승인한 동작의 결과를 모아 사용자의 질문 없이 turn 하나를 돌린다(ADR-040,
     * ADR-050).
     *
     * <p>부르는 쪽이 그 대화의 turn 잠금을 이미 잡았다. 잠금을 잡기 전에 읽은 목록은 다른 자동 turn 이 이미 전했을 수
     * 있어 여기서 다시 읽는다. 비었으면 아무것도 남기지 않고 돌아간다.
     *
     * <p>알림 줄은 위임 결과에 한 줄, 그 밖의 결과마다 한 줄이다. 알림 줄 저장, 결과마다 전했다는 표시, 자동 turn 수
     * 증가, 전달 묶음과 항목과 첫 시도 저장은 한 트랜잭션이다(ADR-075). 그 뒤 Hermes 가 실패해도 같은
     * 결과로 다시 깨우지 않는다. 같은 실패를 되풀이하지 않기 위해서다. 실패는 시도에 남기고 예외로 올라간다.
     *
     * @param owner 대화 주인. 요청이 없으므로 부르는 쪽이 사용자 행으로 만든다
     * @param onEvent 알림 줄, {@code started}, 답 조각, {@code done} 이나 {@code stopped} 를 받는다
     */
    void runDelegationResults(
            CurrentUser owner, Long conversationId, TurnHandle handle, Consumer<ChatEvent> onEvent) {
        List<AgentExecution> results = executionRepository.findUndeliveredResults(conversationId);
        Map<Long, Agent> resultAgents = chatDeliveryInput.resultAgentsOf(results);
        List<AutoTurnDelivery> deliveries = new ArrayList<>();
        List<AutoTurnResult> extras = new ArrayList<>();
        List<String> notices = new ArrayList<>();
        if (!results.isEmpty()) {
            notices.add(ChatDeliveryInput.delegationNotice(results, resultAgents));
        }
        // 승인한 동작의 결과처럼 다른 패키지가 가진 결과를 같은 turn 에 모은다. 위임 결과가 없어도 돈다.
        for (AutoTurnResultSource source : resultSources) {
            List<AutoTurnResult> extra = source.undelivered(conversationId);
            if (extra.isEmpty()) {
                continue;
            }
            extras.addAll(extra);
            extra.forEach(result -> notices.add(result.notice()));
            deliveries.add(new AutoTurnDelivery(
                    source, extra.stream().map(AutoTurnResult::key).toList()));
        }
        if (notices.isEmpty()) {
            return;
        }
        DeliveryInput delivery =
                ChatDeliveryInput.deliveryInput(results, resultAgents, extras, clock.instant(), contextProperties.resultStaleAfter());
        Routed routed = chatTurnRouting.route(owner, conversationId, delivery.input(), null, List.of());
        if (routed.flow() != null) {
            // 흐름은 이 입력을 받을 자리가 없다. 깨우는 쪽이 이미 거르므로 그 사이 흐름이 붙은 경우뿐이다.
            // 깨우는 쪽이 거르는 것과 별개로 남긴다. 거르기와 잠금 사이에 에이전트의 흐름이 바뀌어도 흐름에 이 입력을 보내지 않는다.
            log.warn("흐름이 붙은 대화라 맡긴 일의 결과를 전하지 않는다 conversationId={}", conversationId);
            return;
        }
        Long attemptId = recordDelivery(routed.conversation(), results, notices, deliveries, onEvent);
        runDeliveryTurn(
                owner,
                routed,
                delivery.input(),
                new TurnIntent.DelegationResults(attemptId, false, delivery.items()),
                handle,
                onEvent);
    }

    /**
     * 저장된 결과만 다시 읽어 전달 묶음 하나를 부모에 다시 넘긴다(ADR-075). 자식 실행과 커넥터 호출은 다시 하지 않는다.
     *
     * <p>대화, 묶음, 상태, 에이전트를 잠금 전에 본다. 잠금 전에 본 상태는 빠른 거절일 뿐이다. 잠금을 연 뒤 항목의 결과를
     * 다시 읽고, 묶음을 조건부 update 로 {@code DELIVERING} 으로 바꾸는 것과 알림 줄과 새 시도를 한 트랜잭션에 적는다.
     * 활성 시도를 하나로 지키는 것은 그 update 다.
     *
     * <p>사람이 요청한 turn 이라 자동 turn 수를 0 으로 돌린다. 사건은 요청한 창에만 간다.
     *
     * @param onEvent 알림 줄, {@code started}, 답 조각, {@code done} 이나 {@code stopped} 를 받는다
     * @throws ApiException {@code CONVERSATION_NOT_FOUND}, {@code DELIVERY_NOT_FOUND}, {@code DELIVERY_NOT_RETRYABLE},
     *     {@code AGENT_NOT_FOUND}, {@code AGENT_DISABLED}, {@code CONVERSATION_BUSY}, {@code USER_BUSY}. 이 예외들은 묶음을
     *     바꾸지 않는다
     */
    void retryDelivery(CurrentUser user, Long conversationId, Long deliveryId, Consumer<ChatEvent> onEvent) {
        Conversation conversation = access.requireOwn(user, conversationId);
        if (!resultDeliveries.require(conversation.id(), deliveryId).status().retryable()) {
            throw ResultDeliveryRecorder.notRetryable();
        }
        Agent agent = agents.requireById(conversation.agentId());
        if (agent.isDeleted() || !agent.isReadableBy(user.id())) {
            throw new ApiException(ErrorCode.AGENT_NOT_FOUND, "no such agent");
        }
        if (!agent.enabled()) {
            throw new ApiException(ErrorCode.AGENT_DISABLED, "this agent is disabled");
        }
        if (flows.find(agent.flow()) != null) {
            throw ResultDeliveryRecorder.notRetryable();
        }
        // 사용자 실행 한도에 닿으면 USER_BUSY 가 그대로 올라간다. 묶음을 바꾸지 않고 다시 시도를 예약하지 않는다.
        TurnHandle handle = turns.open(user.id(), conversation.id());
        try {
            DeliveryInput delivery = chatDeliveryInput.retryInput(user, conversation.id(), resultDeliveries.itemsOf(deliveryId));
            Routed routed = chatTurnRouting.route(user, conversation.id(), delivery.input(), null, List.of());
            // 잠금 전에 본 에이전트는 그 사이 바뀌었을 수 있다. 자동 turn 이 잠금 뒤 흐름을 다시 거르는 것과 같다.
            if (routed.flow() != null) {
                throw ResultDeliveryRecorder.notRetryable();
            }
            if (!routed.agent().isReadableBy(user.id())) {
                throw new ApiException(ErrorCode.AGENT_NOT_FOUND, "no such agent");
            }
            Long attemptId = recordRetry(routed.conversation(), deliveryId, onEvent);
            runDeliveryTurn(
                    user,
                    routed,
                    delivery.input(),
                    new TurnIntent.DelegationResults(attemptId, true, delivery.items()),
                    handle,
                    onEvent);
        } finally {
            turns.close(handle);
        }
    }

    /**
     * 그 대화의 알림 줄 번호마다 그 줄이 마지막 시도의 마지막 알림 줄인 묶음의 번호와 상태다. 이력 API 가 쓴다.
     *
     * <p>주인 확인은 부르는 쪽이 마친 번호로 부른다.
     */
    Map<Long, DeliveryState> deliveryStates(Long conversationId) {
        return resultDeliveries.statesByNotice(conversationId);
    }

    /**
     * 다시 전달을 시작한다. 묶음을 {@code DELIVERING} 으로 바꾸는 조건부 update, 알림 줄 저장, 새 시도 저장, 자동 turn 수
     * 초기화를 한 트랜잭션에 적고 새 시도의 번호를 돌려준다. 커밋한 뒤 알림 줄의 {@code system} 사건을 낸다.
     *
     * <p>그 사이 다른 요청이 묶음을 먼저 바꿨으면 {@code DELIVERY_NOT_RETRYABLE} 로 되돌아가 알림 줄도 시도도 남지 않는다.
     */
    Long recordRetry(Conversation conversation, Long deliveryId, Consumer<ChatEvent> onEvent) {
        record Saved(ChatMessage line, Long attemptId) {}
        Saved saved = transactions.execute(status -> {
            Instant now = Instant.now(clock);
            int attemptNo = resultDeliveries.claimRetry(deliveryId, now);
            ChatMessage line = messages.save(ChatMessage.fromSystem(conversation.id(), ChatService.RETRY_NOTICE, now));
            Long attemptId = resultDeliveries.addAttempt(deliveryId, attemptNo, line.id(), now);
            // 사람이 요청한 turn 이라 사용자의 질문 없이 연 turn 의 수를 새로 센다.
            conversationWriter.resetAutoTurns(conversation.id());
            return new Saved(line, attemptId);
        });
        onEvent.accept(ChatEvent.system(
                conversation.publicId(), saved.line().id(), saved.line().content()));
        return saved.attemptId();
    }

    /**
     * 알림 줄, 전했다는 표시, 자동 turn 수, 전달 묶음과 항목과 첫 시도를 한 트랜잭션에 적고 그 시도의 번호를 돌려준다.
     *
     * <p>알림 줄이 곧 전했다는 표시다. 이 쓰기들이 모두 함께 남거나 함께 빠진다. 제목은 채우지 않는다. 항목은 위임 결과가 먼저이고
     * 그 뒤로 결과를 낸 쪽의 순서대로다. 저장한 알림 줄마다 {@code system} 사건을 낸다.
     */
    Long recordDelivery(
            Conversation conversation,
            List<AgentExecution> results,
            List<String> notices,
            List<AutoTurnDelivery> deliveries,
            Consumer<ChatEvent> onEvent) {
        List<DeliveryItemRef> items = new ArrayList<>();
        results.forEach(result ->
                items.add(new DeliveryItemRef(ResultDeliveryRecorder.DELEGATION_SOURCE, String.valueOf(result.id()))));
        deliveries.forEach(delivery -> delivery.keys()
                .forEach(key -> items.add(new DeliveryItemRef(delivery.source().source(), key))));
        record Saved(List<ChatMessage> lines, Long attemptId) {}
        Saved saved = transactions.execute(status -> {
            Instant deliveredAt = Instant.now(clock);
            List<ChatMessage> lines = notices.stream()
                    .map(notice -> messages.save(ChatMessage.fromSystem(conversation.id(), notice, deliveredAt)))
                    .toList();
            results.forEach(result -> deliveryWriter.markResultDelivered(result.id(), deliveredAt));
            deliveries.forEach(delivery -> delivery.source().markDelivered(delivery.keys(), deliveredAt));
            conversationWriter.incrementAutoTurns(conversation.id());
            ResultDeliveryStart start = resultDeliveries.open(
                    conversation.id(), items, lines.getLast().id(), deliveredAt);
            return new Saved(lines, start.attemptId());
        });
        saved.lines()
                .forEach(line -> onEvent.accept(ChatEvent.system(conversation.publicId(), line.id(), line.content())));
        return saved.attemptId();
    }

    /**
     * 전달 시도 하나로 부모 turn 을 돌리고, 그 turn 이 끝난 방식으로 시도를 닫은 뒤 끝 사건을 낸다(ADR-075).
     *
     * <p>답을 남기면 {@code SUCCEEDED}, 중지로 끝나면 {@code STOPPED} 다. 예외로 끝났어도 사용자가 중지를 확정했으면
     * {@code STOPPED} 다. 그 밖의 예외는 {@code FAILED} 와 그 예외의 오류 코드이고, 원래 예외를 다시 던진다.
     * {@code Error} 로 끝나도 시도가 {@code RUNNING} 으로 남지 않게 {@code finally} 에서 닫는다.
     *
     * <p>끝 사건은 시도를 닫은 뒤에 낸다. 화면이 끝 사건을 받고 이력을 다시 읽을 때 묶음 상태가 이미 바뀌어 있어야 한다.
     */
    void runDeliveryTurn(
            CurrentUser owner,
            Routed routed,
            String input,
            TurnIntent.DelegationResults intent,
            TurnHandle handle,
            Consumer<ChatEvent> onEvent) {
        Long attemptId = intent.attemptId();
        boolean closed = false;
        ChatTurn turn;
        try {
            turn = chatTurnRunner.runTurn(owner, routed, input, intent, onEvent, true, handle);
            closed = true;
            closeAttempt(
                    attemptId,
                    turn.cancelled() ? DeliveryAttemptStatus.STOPPED : DeliveryAttemptStatus.SUCCEEDED,
                    null);
        } catch (RuntimeException ex) {
            closed = true;
            if (turns.isStopConfirmed(handle)) {
                closeAttempt(attemptId, DeliveryAttemptStatus.STOPPED, null);
            } else {
                String code = ex instanceof ApiException api ? api.code().name() : ErrorCode.INTERNAL_ERROR.name();
                closeAttempt(attemptId, DeliveryAttemptStatus.FAILED, code);
            }
            throw ex;
        } finally {
            if (!closed) {
                closeAttempt(attemptId, DeliveryAttemptStatus.FAILED, ErrorCode.INTERNAL_ERROR.name());
            }
        }
        onEvent.accept(
                turn.cancelled()
                        ? ChatEvent.stopped(turn.conversationPublicId(), turn.messageId(), turn.executionId())
                        : ChatEvent.done(turn.conversationPublicId(), turn.messageId(), turn.executionId()));
    }

    /**
     * 전달 시도를 닫는다. 닫다 실패해도 turn 의 결과나 원래 예외를 바꾸지 않고 경고 로그만 남긴다.
     *
     * <p>그렇게 남은 시도는 다음 기동의 정리가 닫는다.
     */
    void closeAttempt(Long attemptId, DeliveryAttemptStatus status, String errorCode) {
        try {
            resultDeliveries.finish(attemptId, status, errorCode);
        } catch (RuntimeException ex) {
            log.warn("전달 시도를 닫지 못했다 attemptId={} status={}", attemptId, status, ex);
        }
    }
}
