package com.bifos.assistant.chat.application;

import com.bifos.assistant.agent.application.AgentConnectorBindings;
import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.chat.application.model.AutoTurnDelivery;
import com.bifos.assistant.chat.application.model.AutoTurnResult;
import com.bifos.assistant.chat.application.model.CheckAnswer;
import com.bifos.assistant.chat.application.model.DeliveryInput;
import com.bifos.assistant.chat.application.model.DeliveryItemRef;
import com.bifos.assistant.chat.application.model.DeliveryState;
import com.bifos.assistant.chat.application.model.ResultDeliveryStart;
import com.bifos.assistant.chat.domain.ChatArtifact;
import com.bifos.assistant.chat.domain.ChatAttachment;
import com.bifos.assistant.chat.domain.ChatMessage;
import com.bifos.assistant.chat.domain.ChatPendingMessage;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.domain.RunSession;
import com.bifos.assistant.chat.domain.type.DeliveryAttemptStatus;
import com.bifos.assistant.chat.domain.type.ConversationPurpose;
import com.bifos.assistant.chat.domain.type.MessageRole;
import com.bifos.assistant.chat.domain.type.ModelSelectionMode;
import com.bifos.assistant.chat.infra.ArtifactStore;
import com.bifos.assistant.chat.infra.ChatMessageRepository;
import com.bifos.assistant.chat.infra.ChatPendingMessageRepository;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.context.AssembledContext;
import com.bifos.assistant.context.ContextAssembler;
import com.bifos.assistant.context.ContextBodyMode;
import com.bifos.assistant.context.ContextFreshness;
import com.bifos.assistant.context.ContextItem;
import com.bifos.assistant.context.ContextProperties;
import com.bifos.assistant.context.ContextSource;
import com.bifos.assistant.context.ContextTrust;
import com.bifos.assistant.context.ResultHeader;
import com.bifos.assistant.hermes.HermesProperties;
import com.bifos.assistant.hermes.HermesRunEventStream;
import com.bifos.assistant.hermes.HermesRunsClient;
import com.bifos.assistant.hermes.ToolDetailScope;
import com.bifos.assistant.hermes.dto.HermesRunCommand;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.hermes.dto.RunEvent;
import com.bifos.assistant.memory.application.MemoryProposer;
import com.bifos.assistant.memory.domain.type.MemoryScope;
import com.bifos.assistant.memory.domain.type.MemorySensitivity;
import com.bifos.assistant.model.domain.ModelChoice;
import com.bifos.assistant.model.domain.type.ModelTier;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.shared.util.ExternalData;
import com.bifos.assistant.skill.application.SkillCommandCatalog;
import com.bifos.assistant.skill.application.SkillUseRecorder;
import com.bifos.assistant.usage.application.ContextSourceRef;
import com.bifos.assistant.usage.application.ExecutionContextSnapshot;
import com.bifos.assistant.usage.application.ExecutionDeliveryWriter;
import com.bifos.assistant.usage.application.ExecutionEventRecorder;
import com.bifos.assistant.usage.application.ExecutionRecorder;
import com.bifos.assistant.usage.application.InternalValuePolicy;
import com.bifos.assistant.usage.application.UserExecutionLimiter;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.ExecutionEvent;
import com.bifos.assistant.usage.domain.type.ExecutionEventType;
import com.bifos.assistant.usage.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.infra.ExecutionEventRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 대화 메시지 하나를 Hermes 실행으로 바꾸고 비용을 기록한다.
 *
 * <p>라우팅은 여기에서만 정한다. 대화의 에이전트가 profile을 고르고, 대화가 시작된 뒤 요청 본문은
 * 그 profile을 바꾸지 못한다.
 *
 * <p>쓸 모델과 effort 는 대화가 고른 값이다. 고르지 않았으면 모델을 빼고 보내 profile 의 기본값으로
 * 돈다. 그 provider 의 계정이 전부 막혀도 다른 모델로 넘기지 않고 {@code PROVIDER_BLOCKED} 로 실패한다.
 * 한 provider 안에서 계정을 돌려 쓰는 것은 Hermes 가 이미 하므로 여기서 하지 않는다.
 *
 * <p>자동 turn 은 알림 줄 저장, 전했다는 표시, 자동 turn 수 증가, 전달 묶음과 첫 시도 저장을 한 트랜잭션에 적는다. 그
 * 시도는 부모 turn 의 실행 줄을 잇고 그 turn 이 끝난 방식으로 닫힌다(ADR-075).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ChatService {
    private static final int TITLE_LIMIT = 60;

    /** 대화 목록 한 쪽의 상한이다. 웹이 더 크게 요청해도 이만큼만 읽는다. */
    public static final int MAX_CONVERSATION_PAGE = 100;

    /** 다시 전달할 때 남기는 알림 줄이다. 결과마다 알림 줄을 다시 남기지 않고 이 한 줄만 남긴다(ADR-075). */
    static final String RETRY_NOTICE = "맡긴 일의 결과를 다시 전해요";

    private final ConversationRepository conversations;
    private final ConversationWriter conversationWriter;
    private final ConversationSessions sessions;
    private final ConversationAccess access;
    private final ChatMessageRepository messages;
    private final AgentService agents;
    private final AgentConnectorBindings connectorBindings;
    private final HermesRunsClient hermes;
    private final HermesRunEventStream eventStream;
    private final HermesProperties hermesProperties;
    private final ExecutionRecorder executions;
    private final ExecutionEventRecorder eventRecorder;
    private final ExecutionEventRepository executionEvents;
    private final AgentExecutionRepository executionRepository;
    private final ExecutionDeliveryWriter deliveryWriter;
    private final ContextAssembler contextAssembler;
    private final MemoryProposer memoryProposer;
    private final StarterSuggestionService starterSuggestions;
    private final FlowRegistry flows;
    private final AttachmentService attachments;
    private final ArtifactStore artifactStore;
    private final ArtifactService artifacts;
    private final TurnCancellation turns;
    private final TransactionTemplate transactions;
    private final SkillCommandCatalog skillCommands;
    private final SkillUseRecorder skillUses;
    private final ModelTierService modelTiers;
    private final Clock clock;
    private final ChatPendingMessageRepository pendingMessages;
    private final List<AutoTurnResultSource> resultSources;
    private final UserExecutionLimiter limiter;
    private final ResultDeliveryRecorder resultDeliveries;
    private final ContextProperties contextProperties;
    private final List<CheckReportReads> checkReportReads;

    public ChatTurn send(CurrentUser user, Long conversationId, String text, String agentCode) {
        return send(user, conversationId, text, agentCode, List.of());
    }

    /**
     * 메시지 하나를 보낸다. 첨부 번호가 있으면 그 메시지에 묶고 사진이 놓인 자리를 Hermes 입력에 알린다.
     *
     * <p>첨부가 하나라도 거절되면 메시지를 저장하지 않고 대화도 새로 만들지 않는다.
     */
    public ChatTurn send(
            CurrentUser user, Long conversationId, String text, String agentCode, List<Long> attachmentIds) {
        Routed routed = route(user, conversationId, text, agentCode, attachmentIds);
        if (routed.flow() != null) {
            return runFlow(user, routed, text, new TurnIntent.Fresh(), event -> {}, false, null);
        }
        return runTurn(user, routed, text, new TurnIntent.Fresh(), event -> {}, false, null);
    }

    public void stream(
            CurrentUser user, Long conversationId, String text, String agentCode, Consumer<ChatEvent> onEvent) {
        stream(user, conversationId, text, agentCode, List.of(), onEvent);
    }

    public void stream(
            CurrentUser user,
            Long conversationId,
            String text,
            String agentCode,
            List<Long> attachmentIds,
            Consumer<ChatEvent> onEvent) {
        Routed routed = route(user, conversationId, text, agentCode, attachmentIds);
        // 잠금을 닫으면 닫기 리스너가 맡긴 일의 결과로 자동 turn 을 연다. 이 turn 의 done 이나 stopped 를 보낸 뒤에
        // 닫아야 클라이언트가 이 turn 의 끝을 자동 turn 의 시작보다 먼저 받는다.
        TurnHandle handle = openTurn(user, routed);
        try {
            if (routed.flow() != null) {
                runFlow(user, routed, text, new TurnIntent.Fresh(), onEvent, true, handle);
                return;
            }
            ChatTurn turn = runTurn(user, routed, text, new TurnIntent.Fresh(), onEvent, true, handle);
            onEvent.accept(
                    turn.cancelled()
                            ? ChatEvent.stopped(turn.conversationPublicId(), turn.messageId(), turn.executionId())
                            : ChatEvent.done(turn.conversationPublicId(), turn.messageId(), turn.executionId()));
        } finally {
            turns.close(handle);
        }
    }

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
    public void runDelegationResults(
            CurrentUser owner, Long conversationId, TurnHandle handle, Consumer<ChatEvent> onEvent) {
        List<AgentExecution> results = executionRepository.findUndeliveredResults(conversationId);
        Map<Long, Agent> resultAgents = resultAgentsOf(results);
        List<AutoTurnDelivery> deliveries = new ArrayList<>();
        List<AutoTurnResult> extras = new ArrayList<>();
        List<String> notices = new ArrayList<>();
        if (!results.isEmpty()) {
            notices.add(delegationNotice(results, resultAgents));
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
                deliveryInput(results, resultAgents, extras, clock.instant(), contextProperties.resultStaleAfter());
        Routed routed = route(owner, conversationId, delivery.input(), null, List.of());
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
    public void retryDelivery(CurrentUser user, Long conversationId, Long deliveryId, Consumer<ChatEvent> onEvent) {
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
            DeliveryInput delivery = retryInput(user, conversation.id(), resultDeliveries.itemsOf(deliveryId));
            Routed routed = route(user, conversation.id(), delivery.input(), null, List.of());
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
    public Map<Long, DeliveryState> deliveryStates(Long conversationId) {
        return resultDeliveries.statesByNotice(conversationId);
    }

    /**
     * 묶음의 항목을 결과를 낸 쪽의 줄에서 다시 읽어 자동 turn 과 같은 모양의 입력과 결과 항목을 만든다.
     *
     * <p>신선도는 지금 시각으로 다시 판정한다. 몇 시간 뒤에 다시 전하면 머리줄에 「오래됨」 이 붙는다(ADR-071).
     *
     * <p>위임 결과는 그 사용자와 그 대화의 끝난 실행 줄만 항목 순서로 쓴다. 그 밖의 결과는 출처 이름이 같은 {@link
     * AutoTurnResultSource} 가 다시 읽는다. 맞는 구현이 없으면 경고 로그만 남기고 뺀다. 지워졌거나 남의 것인 결과는 빼고
     * 넘긴다.
     *
     * @throws ApiException {@code DELIVERY_NOT_RETRYABLE}. 남은 결과가 없을 때
     */
    private DeliveryInput retryInput(CurrentUser user, Long conversationId, List<DeliveryItemRef> items) {
        List<Long> executionIds = new ArrayList<>();
        Map<String, List<String>> keysBySource = new LinkedHashMap<>();
        for (DeliveryItemRef item : items) {
            if (!ResultDeliveryRecorder.DELEGATION_SOURCE.equals(item.source())) {
                keysBySource
                        .computeIfAbsent(item.source(), source -> new ArrayList<>())
                        .add(item.resultKey());
                continue;
            }
            try {
                executionIds.add(Long.valueOf(item.resultKey()));
            } catch (NumberFormatException ex) {
                log.warn("실행 번호로 읽지 못하는 위임 결과 항목을 뺀다 resultKey={}", item.resultKey());
            }
        }
        Map<Long, AgentExecution> found = executionRepository.findAllById(executionIds).stream()
                .filter(execution -> user.id().equals(execution.userId())
                        && conversationId.equals(execution.conversationId())
                        && (execution.status() == ExecutionStatus.SUCCEEDED
                                || execution.status() == ExecutionStatus.FAILED))
                .collect(Collectors.toMap(AgentExecution::id, execution -> execution));
        List<AgentExecution> results = executionIds.stream()
                .distinct()
                .map(found::get)
                .filter(Objects::nonNull)
                .toList();
        List<AutoTurnResult> extras = new ArrayList<>();
        keysBySource.forEach((source, keys) -> resultSources.stream()
                .filter(candidate -> candidate.source().equals(source))
                .findFirst()
                .ifPresentOrElse(
                        candidate -> extras.addAll(candidate.resultsFor(conversationId, user.id(), keys)),
                        () -> log.warn("출처 이름에 맞는 결과 구현이 없어 그 항목을 뺀다 source={}", source)));
        if (results.isEmpty() && extras.isEmpty()) {
            throw ResultDeliveryRecorder.notRetryable();
        }
        return deliveryInput(
                results, resultAgentsOf(results), extras, clock.instant(), contextProperties.resultStaleAfter());
    }

    /**
     * 다시 전달을 시작한다. 묶음을 {@code DELIVERING} 으로 바꾸는 조건부 update, 알림 줄 저장, 새 시도 저장, 자동 turn 수
     * 초기화를 한 트랜잭션에 적고 새 시도의 번호를 돌려준다. 커밋한 뒤 알림 줄의 {@code system} 사건을 낸다.
     *
     * <p>그 사이 다른 요청이 묶음을 먼저 바꿨으면 {@code DELIVERY_NOT_RETRYABLE} 로 되돌아가 알림 줄도 시도도 남지 않는다.
     */
    private Long recordRetry(Conversation conversation, Long deliveryId, Consumer<ChatEvent> onEvent) {
        record Saved(ChatMessage line, Long attemptId) {}
        Saved saved = transactions.execute(status -> {
            Instant now = Instant.now(clock);
            int attemptNo = resultDeliveries.claimRetry(deliveryId, now);
            ChatMessage line = messages.save(ChatMessage.fromSystem(conversation.id(), RETRY_NOTICE, now));
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
    private Long recordDelivery(
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
    private void runDeliveryTurn(
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
            turn = runTurn(owner, routed, input, intent, onEvent, true, handle);
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
     * 예약 작업의 지시로 사용자의 질문 없이 turn 하나를 돌린다(ADR-076).
     *
     * <p>부르는 쪽이 그 대화의 turn 잠금을 이미 잡았다. 사람이 보낸 turn 과 같은 경로를 탄다. 그래서 커넥터 도구 판정과
     * 승인 줄, 승인 요청 알림이 사람이 보낸 turn 과 같다. 알림 줄과 지시를 사용자 메시지로 남기고 자동 turn 수를 0 으로
     * 돌리는 것은 한 트랜잭션이다. 실패는 예외로 올라간다.
     *
     * @param owner 대화 주인. 요청이 없으므로 부르는 쪽이 사용자 행으로 만든다
     * @param notice 지시 앞에 대화에 남기는 알림 줄의 글
     * @param instruction 사용자 메시지로 남기고 Hermes 에 보내는 작업의 지시
     * @param onEvent 알림 줄, 사용자 메시지, {@code started}, 답 조각, {@code done} 이나 {@code stopped} 를 받는다
     * @throws ApiException 대화의 에이전트에 흐름이 붙었으면 {@code TASK_AGENT_NOT_SUPPORTED}
     */
    public ChatTurn runScheduledTurn(
            CurrentUser owner,
            Long conversationId,
            TurnHandle handle,
            String notice,
            String instruction,
            Consumer<ChatEvent> onEvent) {
        Routed routed = route(owner, conversationId, instruction, null, List.of());
        if (routed.flow() != null) {
            // 흐름은 지시를 흐름 안에서 저장해 알림 줄과 한 트랜잭션으로 묶을 수 없다. 작업을 만들 때 이미 거른다.
            throw new ApiException(ErrorCode.TASK_AGENT_NOT_SUPPORTED, "an agent with a flow cannot run a task");
        }
        ChatTurn turn = runTurn(owner, routed, instruction, new TurnIntent.Scheduled(notice), onEvent, true, handle);
        onEvent.accept(
                turn.cancelled()
                        ? ChatEvent.stopped(turn.conversationPublicId(), turn.messageId(), turn.executionId())
                        : ChatEvent.done(turn.conversationPublicId(), turn.messageId(), turn.executionId()));
        return turn;
    }

    /**
     * 전달 시도를 닫는다. 닫다 실패해도 turn 의 결과나 원래 예외를 바꾸지 않고 경고 로그만 남긴다.
     *
     * <p>그렇게 남은 시도는 다음 기동의 정리가 닫는다.
     */
    private void closeAttempt(Long attemptId, DeliveryAttemptStatus status, String errorCode) {
        try {
            resultDeliveries.finish(attemptId, status, errorCode);
        } catch (RuntimeException ex) {
            log.warn("전달 시도를 닫지 못했다 attemptId={} status={}", attemptId, status, ex);
        }
    }

    /**
     * 먼저 살펴보기 turn 하나를 돌린다(ADR-080). 살펴보기만의 일은 {@code check} 가 맡는다.
     *
     * <p>부르는 쪽이 그 대화의 turn 잠금을 이미 잡았다. 입력은 한 번만 만들어 라우팅과 turn 에 같은 값을 넘긴다.
     * 흐름이 붙은 대화면 {@code PROACTIVE_CHECK_UNAVAILABLE} 로 거절한다. session 을 바꿀 차례면 turn 을 열기 전에 바꾼다.
     *
     * <p>질문 대신 시작 알림 줄을 남기고, 답 조각은 흘리지 않는다. 성공한 답은 {@code check} 가 바꾼 글로 남기고, 멈추면
     * 그때까지의 답 대신 멈춤 알림 줄만 남긴다. Memory 제안, 추천 질문 갱신, 자동 turn 수, 제목은 건드리지 않는다. 실패는
     * 예외로 올라가고 실패 알림 줄은 부르는 쪽이 남긴다.
     *
     * @param owner 대화 주인
     * @param onEvent 알림 줄, {@code started}, 도구와 하위 에이전트 사건, {@code done} 이나 {@code stopped} 를 받는다
     */
    public void runProactiveCheck(
            CurrentUser owner, Long conversationId, TurnHandle handle, CheckTurn check, Consumer<ChatEvent> onEvent) {
        String input = check.input();
        Routed routed = route(owner, conversationId, input, null, List.of());
        if (routed.flow() != null) {
            throw new ApiException(
                    ErrorCode.PROACTIVE_CHECK_UNAVAILABLE, "an agent with a flow does not run a proactive check");
        }
        if (check.renewSession()) {
            sessions.renew(routed.conversation());
        }
        ChatTurn turn = runTurn(owner, routed, input, new TurnIntent.ProactiveCheck(check), onEvent, true, handle);
        onEvent.accept(
                turn.cancelled()
                        ? ChatEvent.stopped(turn.conversationPublicId(), turn.messageId(), turn.executionId())
                        : ChatEvent.done(turn.conversationPublicId(), turn.messageId(), turn.executionId()));
    }

    /** 읽은 대기 행이 저장 전에 취소돼 다시 읽는 횟수의 상한이다. */
    private static final int PENDING_READ_ATTEMPTS = 3;

    /**
     * 쌓인 대기 메시지를 합쳐 사용자 메시지 하나로 turn 을 돌린다(ADR-048).
     *
     * <p>부르는 쪽이 그 대화의 turn 잠금을 이미 잡았다. 잠금을 잡기 전에 읽은 대기 줄은 그 사이 취소되거나 멈췄을 수
     * 있어 여기서 다시 읽는다. 비었거나 멈춘 행이 있으면 아무것도 남기지 않고 돌아간다.
     *
     * <p>대기 행 삭제와 사용자 메시지 저장은 한 트랜잭션이다. 읽은 뒤 저장 전에 취소된 행이 있으면 되돌리고 다시
     * 읽어 합친다. 사용자 메시지를 저장하기 전의 실패는 예외로 올라가고 대기 행은 그대로 남는다.
     *
     * @param owner 대화 주인. 요청이 없으므로 부르는 쪽이 사용자 행으로 만든다
     * @param onEvent {@code user}, {@code pending}, {@code started}, 답 조각, {@code done} 이나 {@code stopped} 를 받는다
     */
    public void runPendingMessages(
            CurrentUser owner, Long conversationId, TurnHandle handle, Consumer<ChatEvent> onEvent) {
        for (int attempt = 1; ; attempt++) {
            List<ChatPendingMessage> rows = pendingMessages.findByConversationIdOrderByIdAsc(conversationId);
            if (rows.isEmpty() || rows.stream().anyMatch(ChatPendingMessage::held)) {
                return;
            }
            String text = ChatPendingMessage.merged(rows);
            Routed routed = route(owner, conversationId, text, null, List.of());
            if (routed.flow() != null) {
                // 흐름은 질문을 흐름 안에서 저장해 대기 행 삭제와 한 트랜잭션으로 묶을 수 없다. 더할 때 이미 거르므로
                // 그 뒤에 흐름이 붙은 경우뿐이다.
                throw new ApiException(ErrorCode.CONVERSATION_BUSY, "this conversation does not take queued messages");
            }
            List<Long> ids = rows.stream().map(ChatPendingMessage::id).toList();
            try {
                ChatTurn turn = runTurn(owner, routed, text, new TurnIntent.Fresh(ids), onEvent, true, handle);
                onEvent.accept(
                        turn.cancelled()
                                ? ChatEvent.stopped(turn.conversationPublicId(), turn.messageId(), turn.executionId())
                                : ChatEvent.done(turn.conversationPublicId(), turn.messageId(), turn.executionId()));
                return;
            } catch (PendingQueueChangedException ex) {
                if (attempt >= PENDING_READ_ATTEMPTS) {
                    throw new ApiException(
                            ErrorCode.CONVERSATION_BUSY, "the queued messages kept changing while sending");
                }
            }
        }
    }

    /**
     * 이 turn 이 중지로 끝났다고 적고 그 대화의 대기 줄을 멈춰 둔다.
     *
     * <p>turn 잠금을 풀기 전에 멈춘다. 잠금을 푼 뒤 닫기 리스너에서 멈추면 그 사이 다른 스레드가 아직 멈추지 않은
     * 행으로 turn 을 연다.
     *
     * <p>대기 줄을 멈추다 실패해도 예외를 올리지 않고 경고 로그만 남긴다. 멈춤 때문에 중지한 turn 이 오류로 끝나거나
     * 원래 예외가 가려지면 안 된다.
     */
    private void markStoppedAndHoldPending(TurnHandle handle, Long conversationId) {
        turns.markStopped(handle);
        try {
            transactions.executeWithoutResult(status -> pendingMessages.markHeld(conversationId, true));
        } catch (RuntimeException ex) {
            log.warn("중지한 turn 의 대기 줄을 멈춰 두지 못했다 conversationId={}", conversationId, ex);
        }
    }

    /**
     * 중지로 끝난 turn 의 취소 기록을 남긴 뒤 대기 줄을 멈추고 그 turn 을 돌려준다.
     *
     * <p>취소 기록이 먼저다. 대기 줄을 먼저 멈추다 실패하면 실행 줄이 {@code CANCELLED} 로 남지 않는다. 취소 기록이
     * 실패해도 대기 줄은 멈춘다. 둘 다 turn 잠금을 풀기 전이다.
     */
    private ChatTurn stoppedTurn(
            TurnHandle handle, PendingTurn pending, HermesRunResult result, ModelChoice choice, Instant startedAt) {
        try {
            return recorded(cancel(pending, result, choice), startedAt);
        } finally {
            markStoppedTurn(handle, pending);
        }
    }

    /**
     * 멈춘 turn 을 적는다. 상한으로 멈춘 살펴보기 turn 은 대기 줄을 멈추지 않고, 나머지는 {@link #markStoppedAndHoldPending} 과 같다.
     *
     * <p>상한으로 멈춘 것은 사용자가 아니라 Control Plane 이라, 살펴보기 동안 사용자가 보낸 대기 메시지를 그대로 다음 turn 으로
     * 보낸다(ADR-080).
     */
    private void markStoppedTurn(TurnHandle handle, PendingTurn pending) {
        if (pending.intent() instanceof TurnIntent.ProactiveCheck proactive
                && !proactive.check().holdPendingOnStop()) {
            turns.markStopped(handle);
            return;
        }
        markStoppedAndHoldPending(handle, pending.conversation().id());
    }

    /**
     * 중지가 확정된 turn 이 예외로 끝날 때 실행 줄을 취소로 남기고 대기 줄을 멈춘다.
     *
     * <p>사용자가 중지를 확정한 실행은 {@code RUNNING} 으로 남지 않는다. 예외가 실행 줄을 이미 {@code FAILED} 로 적은
     * 뒤라면 그대로 둔다. 취소 기록이 실패해도 대기 줄은 멈추고, 어느 쪽 실패도 올리지 않아 원래 예외가 그대로 올라간다.
     */
    private void cancelAndHoldIfStopConfirmed(TurnHandle handle, PendingTurn pending, ModelChoice choice) {
        if (!turns.isStopConfirmed(handle)) {
            return;
        }
        try {
            if (pending.execution().status() == ExecutionStatus.RUNNING) {
                cancel(pending, null, choice);
            }
        } catch (RuntimeException ex) {
            log.warn(
                    "중지한 turn 의 취소를 기록하지 못했다 executionId={}",
                    pending.execution().id(),
                    ex);
        } finally {
            markStoppedTurn(handle, pending);
        }
    }

    /**
     * 중지로 끝난 흐름 turn 의 결과물을 묶은 뒤 대기 줄을 멈춘다.
     *
     * <p>흐름이 취소를 이미 기록했다. 결과물 묶기가 던져도 잠금을 풀기 전에 대기 줄을 멈춘다.
     */
    private void recordStoppedFlow(TurnHandle handle, ChatTurn turn, Instant startedAt) {
        try {
            recorded(turn, startedAt);
        } finally {
            markStoppedAndHoldPending(handle, turn.conversationId());
        }
    }

    /**
     * 중지가 확정된 흐름 turn 이 예외로 끝날 때 루트 실행 줄을 취소로 남기고 대기 줄을 멈춘다.
     *
     * <p>흐름이 루트 실행을 이미 끝난 상태로 적었으면 그대로 둔다. 실행 줄을 다시 읽어 본다. 흐름이 들고 있는 객체의
     * 상태를 여기서는 알 수 없다. {@code rootExecutionId} 가 null 이면 실행 줄을 만들기 전에 끝난 것이다.
     */
    private void cancelFlowAndHoldIfStopConfirmed(TurnHandle handle, Long conversationId, Long rootExecutionId) {
        if (!turns.isStopConfirmed(handle)) {
            return;
        }
        try {
            if (rootExecutionId != null) {
                executionRepository
                        .findById(rootExecutionId)
                        .filter(execution -> execution.status() == ExecutionStatus.RUNNING)
                        .ifPresent(executions::cancel);
            }
        } catch (RuntimeException ex) {
            log.warn("중지한 흐름 turn 의 취소를 기록하지 못했다 executionId={}", rootExecutionId, ex);
        } finally {
            markStoppedAndHoldPending(handle, conversationId);
        }
    }

    /**
     * 결과마다 출처 머리줄을 두고, 답이 있으면 그 아래에 잇는다. 머리줄에는 에이전트 이름, 실행 번호, 상태, 끝난 시각을 적고 실패는
     * 오류 코드를 더한다. 오래된 결과는 신선도와 안내 한 줄을 더한다(ADR-071).
     *
     * <p>연결용 에이전트의 답은 외부 서비스의 글을 담으므로 {@code <external-data>} 로 감싸 지시가 아니라고 알린다.
     * 에이전트 행이 없는 결과도 출처를 모르므로 감싼다. 감싸도 모델이 그 글을 따르지 않는다는 보장은 없다(ADR-049).
     *
     * <p>결과마다 {@code DELEGATION_RESULT} 항목을 하나씩 만든다. 본문은 입력에만 싣고 항목에 두지 않는다.
     */
    private static DeliveryInput delegationInput(
            List<AgentExecution> results, Map<Long, Agent> resultAgents, Instant now, Duration staleAfter) {
        StringBuilder input = new StringBuilder("맡긴 일의 결과가 도착했다.");
        List<ContextItem> items = new ArrayList<>();
        for (AgentExecution result : results) {
            boolean external = isExternalResult(result, resultAgents);
            ContextItem item = delegationItem(result, external, now, staleAfter);
            items.add(item);
            List<String> fields = new ArrayList<>(List.of(
                    "에이전트: " + agentName(result, resultAgents),
                    "실행 번호: " + result.id(),
                    "상태: " + result.status().name()));
            if (result.status() == ExecutionStatus.FAILED) {
                fields.add("오류: " + result.errorCode());
            }
            input.append("\n\n").append(ResultHeader.render("맡긴 일", fields, item.asOf(), item.freshness(), staleAfter));
            if (result.outputText() != null && !result.outputText().isBlank()) {
                input.append('\n').append(external ? ExternalData.wrap(result.outputText()) : result.outputText());
            }
        }
        return new DeliveryInput(input.toString(), items);
    }

    /**
     * 위임 결과 하나의 문맥 항목이다. 그 사용자와 그 대화의 실행만 오므로 실행 줄의 사용자가 대화 주인이다.
     *
     * @param external 커넥터 에이전트의 답이거나 출처를 모르는 답이다
     */
    private static ContextItem delegationItem(
            AgentExecution result, boolean external, Instant now, Duration staleAfter) {
        ContextFreshness freshness = ResultHeader.freshnessOf(result.finishedAt(), now, staleAfter);
        return new ContextItem(
                ContextSource.DELEGATION_RESULT,
                "execution:" + result.id(),
                MemoryScope.USER,
                result.userId(),
                MemorySensitivity.SENSITIVE,
                external ? ContextTrust.EXTERNAL : ContextTrust.AGENT,
                result.finishedAt(),
                freshness,
                ContextBodyMode.INLINE,
                List.of(),
                null,
                null);
    }

    /**
     * 자동 turn 과 다시 전달이 함께 쓰는 Hermes 입력과 결과 항목이다. 위임 결과의 단락이 먼저이고, 그 뒤로 그 밖의 결과의 단락을
     * 빈 줄로 잇는다. 항목도 같은 순서다. 항목이 없는 그 밖의 결과는 단락만 싣는다.
     *
     * @param now 묶음을 만든 시각. 결과의 신선도를 이 시각으로 판정한다
     */
    private static DeliveryInput deliveryInput(
            List<AgentExecution> results,
            Map<Long, Agent> resultAgents,
            List<AutoTurnResult> extras,
            Instant now,
            Duration staleAfter) {
        StringBuilder input = new StringBuilder();
        List<ContextItem> items = new ArrayList<>();
        if (!results.isEmpty()) {
            DeliveryInput delegation = delegationInput(results, resultAgents, now, staleAfter);
            input.append(delegation.input());
            items.addAll(delegation.items());
        }
        for (AutoTurnResult extra : extras) {
            if (!input.isEmpty()) {
                input.append("\n\n");
            }
            input.append(extra.input());
            if (extra.item() != null) {
                items.add(extra.item());
            }
        }
        return new DeliveryInput(input.toString(), items);
    }

    /** 위임 결과를 낸 에이전트들이다. 결과가 없으면 읽지 않는다. */
    private Map<Long, Agent> resultAgentsOf(List<AgentExecution> results) {
        return results.isEmpty()
                ? Map.of()
                : agents.byIds(results.stream().map(AgentExecution::agentId).toList());
    }

    private static boolean isExternalResult(AgentExecution execution, Map<Long, Agent> resultAgents) {
        Agent agent = resultAgents.get(execution.agentId());
        return agent == null || agent.connectorManaged();
    }

    private static String delegationNotice(List<AgentExecution> results, Map<Long, Agent> resultAgents) {
        String first = agentName(results.getFirst(), resultAgents);
        if (results.size() == 1) {
            return first + " 에이전트의 결과가 도착했어요";
        }
        return first + " 외 " + (results.size() - 1) + "개 에이전트의 결과가 도착했어요";
    }

    /** 에이전트 행이 없으면 실행 줄에 적힌 profile 이름으로 대신한다. */
    private static String agentName(AgentExecution execution, Map<Long, Agent> resultAgents) {
        Agent agent = resultAgents.get(execution.agentId());
        return agent == null ? execution.profileName() : agent.name();
    }

    /**
     * 실행에 남길 문맥 항목의 참조다. 결과를 전하는 turn 이면 Memory 항목 뒤로 입력에 실은 결과 항목을 잇는다(ADR-071).
     *
     * <p>커넥터 에이전트의 turn 은 Memory 항목이 없어 결과 항목만 남는다.
     */
    private static List<ContextSourceRef> sourceRefs(AssembledContext context, TurnIntent intent) {
        if (!(intent instanceof TurnIntent.DelegationResults results)
                || results.items().isEmpty()) {
            return ContextSourceRefs.of(context);
        }
        List<ContextSourceRef> refs = new ArrayList<>(ContextSourceRefs.of(context));
        refs.addAll(ContextSourceRefs.of(results.items()));
        return refs;
    }

    /**
     * 대화가 고른 모델로 turn 하나를 끝낸다.
     *
     * <p>provider 의 계정이 전부 막히면 그 실행을 {@code PROVIDER_BLOCKED} 로 실패시키고 끝낸다. 다른
     * 모델로 넘기지 않는다. 어느 모델을 쓸지는 사용자가 대화에서 고르기 때문이다.
     *
     * <p>사용자 메시지를 저장하고 첨부를 묶는 것은 한 트랜잭션이다. 그 사이에 다른 요청이 같은 첨부를
     * 먼저 묶으면 메시지 저장도 되돌린다. 빈 제목을 채우는 것도 같은 트랜잭션이라 함께 되돌린다.
     * Hermes 호출은 그 트랜잭션 밖이다. 저장하는 본문은 사용자가 쓴
     * 그대로이고, 사진 자리는 Hermes 입력에만 붙인다.
     *
     * <p>스킬 커맨드이면 Hermes 입력의 글 자리만 {@link SkillCommand#hermesInput()} 으로 바꾸고, 실행 줄을
     * 만든 뒤 그 실행에 {@code COMMAND} 이력을 남긴다. 저장하는 메시지는 그대로 사용자가 친 글이다.
     */
    private ChatTurn runTurn(
            CurrentUser user,
            Routed routed,
            String text,
            TurnIntent intent,
            Consumer<ChatEvent> onEvent,
            boolean streaming,
            TurnHandle existingHandle) {
        Instant requestReceivedAt = routed.requestReceivedAt();
        Conversation conversation = routed.conversation();
        List<Long> attachmentIds =
                routed.attached().stream().map(ChatAttachment::id).toList();
        TurnHandle handle = existingHandle == null ? openTurn(user, routed) : existingHandle;
        boolean closesHandle = existingHandle == null;
        try {
            saveQuestion(user, conversation, text, attachmentIds, intent, onEvent);
            // 폴더를 만들기 전에 잡는다. 이 시각 뒤에 바뀐 HTML 이 이 turn 의 결과물이다.
            Instant startedAt = clock.instant();
            artifactStore.ensureFolder(conversation.id());
            SkillCommand command = routed.command();
            String asked = command == null ? text : command.hermesInput();
            String input = artifacts.agentPreamble(conversation)
                    + attachments.agentInput(conversation.id(), routed.attached(), asked);
            // 커넥터 에이전트의 실행에는 Memory 문맥을 주지 않는다(ADR-045). turn 지시는 그대로 붙는다.
            AssembledContext context = routed.agent().connectorManaged()
                    ? AssembledContext.empty()
                    : contextAssembler.assemble(user, routed.agent().id());
            context = contextAssembler.withResponseInstructions(context);
            ExecutionContextSnapshot snapshot = new ExecutionContextSnapshot(
                    context.chars(),
                    null,
                    context.instructionsHash(),
                    context.omittedItems(),
                    sourceRefs(context, intent));

            ResolvedModelTier resolved;
            try {
                resolved = modelTiers.resolve(user, conversation, routed.agent());
            } catch (RuntimeException ex) {
                PendingTurn failed = begin(
                        user,
                        routed,
                        input,
                        context,
                        snapshot,
                        ModelChoice.defaults(),
                        null,
                        intent,
                        requestReceivedAt,
                        onEvent);
                String code = ex instanceof ApiException api ? api.code().name() : "MODEL_TIER_RESOLVE_FAILED";
                executions.fail(failed.execution(), code);
                append(failed, ExecutionEventType.RUN_FAILED, code);
                throw ex;
            }
            ModelChoice choice = resolved.choice();
            PendingTurn pending = begin(
                    user,
                    routed,
                    input,
                    context,
                    snapshot,
                    choice,
                    resolved.tier(),
                    intent,
                    requestReceivedAt,
                    onEvent);
            if (command != null) {
                skillUses.recordCommand(pending.execution().id(), command.name());
            }
            turns.rekey(handle, pending.execution().id());
            if (intent instanceof TurnIntent.ProactiveCheck proactive) {
                startCheck(proactive.check(), pending);
            }
            if (streaming) {
                onEvent.accept(ChatEvent.started(
                        conversation.publicId(), pending.execution().id()));
            }

            if (turns.isStopConfirmed(handle)
                    || turns.shouldStopBeforeSubmit(pending.execution().id())) {
                return stoppedTurn(handle, pending, null, choice, startedAt);
            }
            String runId = null;
            HermesRunResult result;
            try {
                runId = submit(pending);
                // 한 번에 받는 경로도 사건 스트림을 연다. 화면으로 흘릴 곳은 없고 도구 사건을 실행 기록에 남기려는 것이다(ADR-090).
                relay(pending, runId, handle, streaming ? onEvent : null);
                result = awaitCompletion(pending, runId);
            } catch (RuntimeException ex) {
                cancelAndHoldIfStopConfirmed(handle, pending, choice);
                throw ex;
            } finally {
                turns.untrackRun(pending.execution().id(), runId);
            }

            if (turns.isStopConfirmed(handle) || "cancelled".equalsIgnoreCase(result.status())) {
                return stoppedTurn(handle, pending, result, choice, startedAt);
            }
            if (result.succeeded()) {
                ChatTurn completed = finish(pending, result, choice);
                turns.markFinished(handle);
                return recorded(completed, startedAt);
            }
            if (result.providerBlocked()) {
                executions.fail(
                        pending.execution(), pending.agent(), result, choice, ErrorCode.PROVIDER_BLOCKED.name());
                append(pending, ExecutionEventType.RUN_FAILED, ErrorCode.PROVIDER_BLOCKED.name());
                throw new ApiException(ErrorCode.PROVIDER_BLOCKED, "every account of the chosen provider is blocked");
            }
            executions.fail(pending.execution(), pending.agent(), result, choice, hermesStatus(result));
            append(pending, ExecutionEventType.RUN_FAILED, hermesStatus(result));
            throw new ApiException(ErrorCode.HERMES_RUN_FAILED, "the agent run did not complete");
        } finally {
            if (closesHandle) {
                turns.close(handle);
            }
        }
    }

    /**
     * 흐름으로 도는 turn 을 중계한다.
     *
     * <p>흐름은 단계 사건만 흘리고 답은 끝난 뒤에 한 번에 온다. 중간 단계의 답까지 흘리면 읽을 수
     * 없기 때문이다. 근거는 ADR-016 에 있다.
     */
    private ChatTurn runFlow(
            CurrentUser user,
            Routed routed,
            String text,
            TurnIntent intent,
            Consumer<ChatEvent> onEvent,
            boolean streaming,
            TurnHandle existingHandle) {
        Conversation conversation = routed.conversation();
        TurnHandle handle = existingHandle == null ? openTurn(user, routed) : existingHandle;
        boolean closesHandle = existingHandle == null;
        try {
            if (intent instanceof TurnIntent.Fresh) {
                fillBlankTitle(conversation, text);
            }
            Instant startedAt = clock.instant();
            artifactStore.ensureFolder(conversation.id());
            String input = artifacts.agentPreamble(conversation)
                    + attachments.agentInput(conversation.id(), routed.attached(), text);
            AtomicReference<Long> rootExecutionId = new AtomicReference<>();
            ChatTurn turn;
            try {
                turn = routed.flow()
                        .run(
                                user,
                                conversation,
                                routed.agent(),
                                text,
                                input,
                                intent,
                                execution -> {
                                    rootExecutionId.set(execution.id());
                                    executions.markRequestReceived(execution, routed.requestReceivedAt());
                                    turns.rekey(handle, execution.id());
                                    if (streaming) {
                                        onEvent.accept(ChatEvent.started(conversation.publicId(), execution.id()));
                                    }
                                },
                                onEvent);
            } catch (RuntimeException ex) {
                cancelFlowAndHoldIfStopConfirmed(handle, conversation.id(), rootExecutionId.get());
                throw ex;
            }
            if (!turn.cancelled()) {
                turns.markFinished(handle);
                recorded(turn, startedAt);
            } else {
                recordStoppedFlow(handle, turn, startedAt);
            }
            if (streaming) {
                if (!turn.cancelled()) {
                    onEvent.accept(ChatEvent.delta(turn.assistantText()));
                }
                onEvent.accept(
                        turn.cancelled()
                                ? ChatEvent.stopped(turn.conversationPublicId(), turn.messageId(), turn.executionId())
                                : ChatEvent.done(turn.conversationPublicId(), turn.messageId(), turn.executionId()));
            }
            return turn;
        } finally {
            if (closesHandle) {
                turns.close(handle);
            }
        }
    }

    /**
     * 이 turn 이 대화 폴더에 만든 HTML 을 답에 묶고 turn 을 그대로 돌려준다.
     *
     * <p>turn 을 돌려주는 자리마다 부른다. 끝 사건을 보내는 자리에 두면 스트림이 아닌 경로가 빠진다. 묶기가
     * 실패해도 turn 은 성공으로 끝난다. 근거는 ADR-027 에 있다.
     */
    private ChatTurn recorded(ChatTurn turn, Instant startedAt) {
        artifacts.recordTurn(turn.conversationId(), turn.messageId(), startedAt);
        return turn;
    }

    /**
     * 대화와 에이전트를 정하고 어느 경로로 갈지 고른다.
     *
     * <p>에이전트에 {@code flow} 가 적혀 있으면 그 흐름으로 간다. 비어 있으면 지금처럼 Hermes 를 한
     * 번 부른다. 모르는 이름은 기동할 때 이미 걸러졌다.
     *
     * <p>첨부 판정을 메시지를 저장하기 전에 모두 끝낸다. 첨부는 대화에 올리므로 첨부가 있으면 대화
     * 번호도 있어야 하고, 그것을 대화를 만들기 전에 본다. 거절은 모두 {@code VALIDATION_FAILED} 다.
     *
     * <p>에이전트를 새 대화를 저장하기 전에 정한다. 새 대화는 {@code agentCode} 의 에이전트, 이어 쓰는 대화는
     * 그 대화의 에이전트다. 스킬 커맨드의 이름이 그 에이전트의 켜진 스킬이 아니면 대화를 만들기 전에
     * {@code SKILL_COMMAND_UNKNOWN} 으로 거절한다. 거절한 커맨드는 대화도 메시지도 실행도 남기지 않는다.
     *
     * <p>새 대화를 저장하기 전에 사용자 자리가 남았는지 본다(ADR-069). 없으면 {@code USER_BUSY} 로 거절하고 아무것도
     * 저장하지 않는다. 그대로 저장하면 거절된 요청마다 그 글을 제목으로 한 빈 대화가 목록에 남는다.
     */
    private Routed route(
            CurrentUser user, Long conversationId, String text, String agentCode, List<Long> attachmentIds) {
        Instant requestReceivedAt = clock.instant();
        boolean withAttachments = attachmentIds != null && !attachmentIds.isEmpty();
        if (withAttachments && conversationId == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "attachments need an existing conversation");
        }
        Conversation existing = conversationId == null ? null : access.requireOwn(user, conversationId);
        Agent agent =
                existing == null ? agents.requireStartable(user, agentCode) : agents.requireById(existing.agentId());
        // 지운 에이전트의 대화는 읽기만 된다. 꺼진 것보다 먼저 봐야 없는 에이전트로 알린다.
        if (agent.isDeleted()) {
            throw new ApiException(ErrorCode.AGENT_NOT_FOUND, "no such agent");
        }
        if (!agent.enabled()) {
            throw new ApiException(ErrorCode.AGENT_DISABLED, "this agent is disabled");
        }
        // 흐름은 사진 자리를 덧붙이는 경로를 거치지 않는다. 오류 없이 사진을 버리지 않게 거절한다.
        if (withAttachments && !agent.acceptsAttachments()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "this agent does not accept attachments");
        }
        requireAttachmentOwner(user, agent, withAttachments);
        Flow flow = flows.find(agent.flow());
        SkillCommand command = commandOf(agent, flow, text);
        if (existing == null && !limiter.hasTurnRoom(user.id())) {
            throw new ApiException(ErrorCode.USER_BUSY, "this user has reached the concurrent execution limit");
        }
        Conversation conversation = existing != null
                ? existing
                : conversations.save(Conversation.startedBy(user.id(), titleFrom(text), agent.id(), clock.instant()));
        List<ChatAttachment> attached = attachments.requireAttachable(conversation.id(), attachmentIds);
        return new Routed(conversation, agent, flow, attached, command, requestReceivedAt, existing == null);
    }

    /**
     * 그 대화의 turn 잠금과 사용자 자리를 얻는다.
     *
     * <p>{@link #route} 가 새 대화를 저장하기 전에 자리를 보았지만, 그 사이 다른 요청이 자리를 채우면 여기서
     * {@code USER_BUSY} 가 난다. 그때 방금 만든 빈 대화를 지우고 다시 던진다. 지우다 실패하면 경고 로그만 남기고 원래
     * 예외를 던진다.
     */
    private TurnHandle openTurn(CurrentUser user, Routed routed) {
        try {
            return turns.open(user.id(), routed.conversation().id());
        } catch (ApiException ex) {
            if (ex.code() == ErrorCode.USER_BUSY && routed.created()) {
                deleteCreatedConversation(routed.conversation().id());
            }
            throw ex;
        }
    }

    /** 예약 작업 발화가 미리 만들었다가 쓰지 않은 대화만 부른다. 메시지가 있으면 지우지 않는다. */
    @Transactional
    public boolean discardEmptyTaskConversation(Long conversationId) {
        return conversations.discardEmptyTaskConversation(conversationId) == 1;
    }

    private void deleteCreatedConversation(Long conversationId) {
        try {
            conversations.deleteById(conversationId);
        } catch (RuntimeException ex) {
            log.warn("사용자 실행 한도로 거절한 요청의 빈 대화를 지우지 못했다 conversationId={}", conversationId, ex);
        }
    }

    /**
     * 메시지가 스킬 커맨드이면 그 이름이 에이전트의 켜진 스킬인지 확인해 낸다. 근거는 ADR-035 에 있다.
     *
     * <p>커맨드 모양이 아니거나 흐름이 붙은 에이전트이면 {@code null} 이다. 흐름에는 글을 그대로 보낸다.
     * 켜진 스킬 목록을 읽다 Hermes 가 실패하면 그 예외가 그대로 올라간다. 이름을 확인하지 못한 커맨드를
     * 보내지 않는다. 그 에이전트를 쓸 수 있는지는 부르기 전에 이미 판정했으므로 목록을 읽을 때 다시 보지 않는다.
     */
    private SkillCommand commandOf(Agent agent, Flow flow, String text) {
        if (flow != null) {
            return null;
        }
        SkillCommand command = SkillCommand.parse(text).orElse(null);
        if (command == null) {
            return null;
        }
        if (!skillCommands.enabledNames(agent).contains(command.name())) {
            throw new ApiException(ErrorCode.SKILL_COMMAND_UNKNOWN, "this agent has no enabled skill with that name");
        }
        return command;
    }

    /**
     * 빈 대화를 먼저 만든 경우 첫 메시지로 제목을 채운다.
     *
     * <p>{@link #route} 에서 채우지 않는다. 그 뒤 첨부 묶기가 실패해 메시지가 되돌려져도 제목만 남기
     * 때문이다. 첨부를 받는 경로는 메시지 저장과 같은 트랜잭션에서 부른다.
     */
    private void fillBlankTitle(Conversation conversation, String text) {
        if (conversation.title().isBlank()) {
            String title = titleFrom(text);
            conversationWriter.fillTitleIfBlank(conversation.id(), title);
            conversation.titleIfBlank(title);
        }
    }

    /**
     * 대화가 고른 값으로 명령과 실행 줄을 만든다. 사용자 메시지는 이미 저장돼 있다.
     *
     * <p>고르지 않은 provider, 모델, effort 는 null 그대로 실어 profile 의 기본값으로 돌게 둔다.
     *
     * <p>보낼 session 을 명령보다 먼저 정한다. 실행 줄에는 그 대화의 루트 session 을 적는다. 압축 교체 뒤에는
     * 보내는 session 과 적는 session 이 다르다(ADR-031).
     */
    private PendingTurn begin(
            CurrentUser user,
            Routed routed,
            String text,
            AssembledContext context,
            ExecutionContextSnapshot snapshot,
            ModelChoice choice,
            ModelTier modelTier,
            TurnIntent intent,
            Instant requestReceivedAt,
            Consumer<ChatEvent> onEvent) {
        Conversation conversation = routed.conversation();
        Agent agent = routed.agent();
        RunSession session = sessions.ensure(conversation);
        HermesRunCommand command = new HermesRunCommand(
                agent.hermesProfile(),
                agent.apiBaseUrl(),
                text,
                TurnIntent.appendTo(AskFormat.appendTo(context.instructions()), intent),
                session.runtimeSessionId(),
                choice.provider(),
                choice.model(),
                choice.reasoningEffort());
        AgentExecution execution = executions.start(
                user,
                conversation.executionConversation(),
                agent,
                null,
                null,
                snapshot,
                choice,
                null,
                session.correlationSessionId(),
                null,
                modelTier,
                requestReceivedAt);
        if (intent instanceof TurnIntent.DelegationResults results) {
            attachDeliveryExecution(results.attemptId(), execution.id());
        }
        return new PendingTurn(
                user,
                conversation,
                agent,
                command,
                execution,
                new SequenceCounter(),
                new StringBuilder(),
                intent,
                onEvent);
    }

    /**
     * 먼저 살펴보기 turn 의 실행 줄을 살펴보기에 잇는다.
     *
     * <p>잇지 못하면 실행 줄을 실패로 적고 던진다. 잇지 않은 채 보내면 그 실행이 살펴보기 트리로 판정되지 않아 읽기 경계 밖에서
     * 돈다.
     */
    private void startCheck(CheckTurn check, PendingTurn pending) {
        try {
            check.started(pending.execution().id(), pending.conversation().hermesRootSessionId());
        } catch (RuntimeException ex) {
            String code = ex instanceof ApiException api ? api.code().name() : ErrorCode.INTERNAL_ERROR.name();
            executions.fail(pending.execution(), code);
            append(pending, ExecutionEventType.RUN_FAILED, code);
            throw ex;
        }
    }

    /**
     * 자동 turn 의 전달 시도에 그 turn 의 실행 줄을 잇는다(ADR-075).
     *
     * <p>실패해도 던지지 않는다. 던지면 방금 만든 실행 줄이 {@code RUNNING} 으로 남는다. 이 뒤에는 그 줄을 실패로 적는
     * 경로가 없다. 시도는 이어지지 않은 채 turn 이 끝난 방식으로 닫힌다.
     */
    private void attachDeliveryExecution(Long attemptId, Long executionId) {
        try {
            resultDeliveries.attachExecution(attemptId, executionId);
        } catch (RuntimeException ex) {
            log.warn("전달 시도에 실행 줄을 잇지 못했다 attemptId={} executionId={}", attemptId, executionId, ex);
        }
    }

    private String submit(PendingTurn pending) {
        try {
            executions.markSubmitted(pending.execution());
            String runId = hermes.submit(pending.command());
            executions.attachRunId(pending.execution(), runId);
            append(pending, ExecutionEventType.RUN_STARTED, null);
            turns.trackRun(
                    pending.execution().id(),
                    pending.command().apiBaseUrl(),
                    pending.command().profileName(),
                    runId);
            return runId;
        } catch (ApiException ex) {
            executions.fail(pending.execution(), ex.code().name());
            append(pending, ExecutionEventType.RUN_FAILED, ex.code().name());
            throw ex;
        }
    }

    /**
     * Hermes 사건 스트림이 닫히거나 중지 유예가 끝날 때까지 읽는다.
     *
     * @param onEvent 사건을 화면으로 흘릴 곳. null 이면 한 번에 받는 경로라 실행 기록에만 남기고 답 조각 시각도 적지 않는다.
     *     이 경로는 스트림을 기다리는 시간에도 {@code hermes.run-timeout} 상한을 두고, 넘으면 스트림을 닫은 채 결과 조회로 넘어간다
     */
    private void relay(PendingTurn pending, String runId, TurnHandle handle, Consumer<ChatEvent> onEvent) {
        // 일부 HTTP 스트림은 다른 스레드의 close 중에도 readLine 을 놓지 않는다.
        // 중지 유예 시간이 지나면 요청 스레드를 먼저 풀어 상태 조회와 stopped 사건으로 진행한다.
        CompletableFuture<Void> streamDone = new CompletableFuture<>();
        ToolDetailScope detailScope = toolDetailScope(pending.agent());
        Thread.startVirtualThread(() -> {
            try {
                eventStream.open(
                        pending.command().apiBaseUrl(),
                        pending.command().profileName(),
                        runId,
                        event -> {
                            synchronized (pending) {
                                if (!handle.cancelled().get() || !turns.isStopConfirmed(handle)) {
                                    if (onEvent == null) {
                                        append(pending, event);
                                    } else {
                                        forward(pending, event, onEvent);
                                    }
                                }
                            }
                        },
                        stream -> turns.attachStream(handle, stream),
                        detailScope);
            } catch (ApiException ex) {
                log.warn("Hermes event stream ended before final status runId={}", runId, ex);
            } catch (RuntimeException ex) {
                // 사건은 관측용이다. 읽다가 예기치 못한 오류가 나도 가려진 스레드 오류로 두지 않고 남긴다
                log.warn("Hermes event stream failed runId={}", runId, ex);
            } finally {
                turns.detachStream(handle);
                streamDone.complete(null);
            }
        });
        if (onEvent == null) {
            turns.awaitStreamOrGrace(handle, streamDone, hermesProperties.runTimeout());
        } else {
            turns.awaitStreamOrGrace(handle, streamDone);
        }
    }

    /**
     * 실행 기록에서 내용을 통째로 가릴 도구다. 옛 커넥터 에이전트는 모두 가리고, 다른 에이전트는 붙은 커넥터 서버의 도구만
     * 가린다(ADR-083). 외부 서비스의 글이 실행 기록에 남지 않게 한다.
     */
    private ToolDetailScope toolDetailScope(Agent agent) {
        if (agent.connectorManaged()) {
            return ToolDetailScope.ALL;
        }
        return ToolDetailScope.prefixes(connectorBindings.connectorToolPrefixes(agent.id()));
    }

    private HermesRunResult awaitCompletion(PendingTurn pending, String runId) {
        try {
            return hermes.awaitCompletion(pending.command(), runId);
        } catch (ApiException ex) {
            // Hermes 에서 끝났는지 모르는 run 은 끝날 때까지 사용자 자리를 쥔다(ADR-069).
            limiter.holdUntilRemoteEnds(
                    pending.user().id(),
                    pending.execution().id(),
                    pending.command().apiBaseUrl(),
                    pending.command().profileName(),
                    runId,
                    false);
            executions.fail(pending.execution(), ex.code().name());
            append(pending, ExecutionEventType.RUN_FAILED, ex.code().name());
            throw ex;
        }
    }

    private ChatTurn finish(PendingTurn pending, HermesRunResult result, ModelChoice requested) {
        Instant now = clock.instant();
        pending.conversation().rememberSession(result.sessionId(), now);
        conversationWriter.touchSession(
                pending.conversation().id(),
                result.sessionId() == null || result.sessionId().isBlank() ? null : result.sessionId(),
                now);

        AgentExecution execution = executions.complete(pending.execution(), pending.agent(), result, requested);
        append(pending, ExecutionEventType.RUN_COMPLETED, null);
        String answer = result.output() == null ? "" : result.output();
        if (pending.intent() instanceof TurnIntent.ProactiveCheck proactive) {
            return finishCheck(pending, proactive.check(), execution.id(), answer);
        }
        ChatMessage message = messages.save(answerMessage(pending, answer, execution.id()));
        memoryProposer.proposeFrom(
                pending.user(),
                pending.conversation().executionConversation(),
                pending.agent(),
                execution,
                answer,
                requested);
        starterSuggestions.refreshIfStale(pending.user(), pending.agent());
        return new ChatTurn(
                pending.conversation().id(),
                pending.conversation().publicId(),
                execution.id(),
                answer,
                message.id(),
                false);
    }

    /**
     * 먼저 살펴보기 turn 의 답을 {@code check} 가 바꾼 글로 남긴다. 알림 줄이면 {@code system} 사건을 낸다.
     *
     * <p>Memory 제안과 추천 질문 갱신을 띄우지 않는다. 사용자의 질문이 없는 turn 이다.
     */
    private ChatTurn finishCheck(PendingTurn pending, CheckTurn check, Long executionId, String output) {
        CheckAnswer checked = check.answer(executionId, output);
        Conversation conversation = pending.conversation();
        if (checked.omit()) {
            return new ChatTurn(conversation.id(), conversation.publicId(), executionId, "", null, false);
        }
        ChatMessage message;
        if (checked.notice()) {
            message = messages.save(ChatMessage.fromSystem(conversation.id(), checked.text(), clock.instant()));
            pending.onEvent().accept(ChatEvent.system(conversation.publicId(), message.id(), message.content()));
        } else {
            message = messages.save(answerMessage(pending, checked.text(), executionId));
        }
        return new ChatTurn(
                conversation.id(), conversation.publicId(), executionId, checked.text(), message.id(), false);
    }

    private ChatTurn cancel(PendingTurn pending, HermesRunResult result, ModelChoice requested) {
        AgentExecution execution = executions.cancel(pending.execution(), pending.agent(), result, requested);
        String answer;
        synchronized (pending) {
            append(pending, ExecutionEventType.RUN_CANCELLED, null);
            answer = result != null
                            && result.output() != null
                            && !result.output().isBlank()
                    ? result.output()
                    : pending.streamed().toString();
        }
        if (pending.intent() instanceof TurnIntent.ProactiveCheck proactive) {
            // 검사하지 않은 답이 대화에 남지 않게 그때까지의 답 대신 멈춤 알림 줄만 남긴다.
            answer = "";
            ChatMessage notice = messages.save(ChatMessage.fromSystem(
                    pending.conversation().id(), proactive.check().stoppedNotice(), clock.instant()));
            pending.onEvent()
                    .accept(ChatEvent.system(pending.conversation().publicId(), notice.id(), notice.content()));
        }
        ChatMessage message = answer.isBlank() ? null : messages.save(answerMessage(pending, answer, execution.id()));
        if (result != null && result.sessionId() != null && !result.sessionId().isBlank()) {
            Instant now = clock.instant();
            pending.conversation().rememberSession(result.sessionId(), now);
            conversationWriter.touchSession(pending.conversation().id(), result.sessionId(), now);
        }
        return new ChatTurn(
                pending.conversation().id(),
                pending.conversation().publicId(),
                execution.id(),
                answer,
                message == null ? null : message.id(),
                true);
    }

    /** 마지막 답을 새 실행으로 다시 만든다. */
    public void regenerate(CurrentUser user, Long conversationId, Consumer<ChatEvent> onEvent) {
        Instant requestReceivedAt = clock.instant();
        Conversation conversation = access.requireOwn(user, conversationId);
        TurnHandle handle = turns.open(user.id(), conversation.id());
        try {
            List<ChatMessage> active = activeMessages(conversation.id());
            if (active.isEmpty()) {
                throw new ApiException(ErrorCode.MESSAGE_NOT_LATEST, "there is no message to regenerate");
            }
            ChatMessage last = active.getLast();
            ChatMessage previousAnswer = last.role() == MessageRole.ASSISTANT ? last : null;
            ChatMessage question = previousAnswer == null ? last : previousQuestion(active, previousAnswer);
            if (question == null || question.role() != MessageRole.USER) {
                throw new ApiException(ErrorCode.MESSAGE_NOT_LATEST, "the latest message is not a question");
            }
            List<ChatAttachment> attached = attachments.allOf(conversation.id()).stream()
                    .filter(attachment -> question.id().equals(attachment.messageId()) && attachment.isVisible())
                    .toList();
            Routed routed = routeExisting(user, conversation, attached, question.content(), requestReceivedAt);
            TurnIntent intent = new TurnIntent.Regenerate(previousAnswer, question);
            if (routed.flow() != null) {
                runFlow(user, routed, question.content(), intent, onEvent, true, handle);
                return;
            }
            ChatTurn turn = runTurn(user, routed, question.content(), intent, onEvent, true, handle);
            onEvent.accept(
                    turn.cancelled()
                            ? ChatEvent.stopped(turn.conversationPublicId(), turn.messageId(), turn.executionId())
                            : ChatEvent.done(turn.conversationPublicId(), turn.messageId(), turn.executionId()));
        } finally {
            turns.close(handle);
        }
    }

    private List<ChatMessage> activeMessages(Long conversationId) {
        List<ChatMessage> history = messages.findByConversationIdOrderByIdAsc(conversationId);
        Set<Long> replaced = history.stream()
                .map(ChatMessage::replacesMessageId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return history.stream()
                .filter(message -> !replaced.contains(message.id()))
                .toList();
    }

    /**
     * 답 앞의 질문을 뒤로 거슬러 찾는다.
     *
     * <p>답 바로 앞이 알림 줄이면 사용자 질문 없이 연 자동 turn 의 답이라 다시 만들 질문이 없다. 그때는 null 이다.
     */
    private static ChatMessage previousQuestion(List<ChatMessage> active, ChatMessage answer) {
        int answerIndex = active.indexOf(answer);
        if (answerIndex > 0 && active.get(answerIndex - 1).role() == MessageRole.SYSTEM) {
            return null;
        }
        for (int index = answerIndex - 1; index >= 0; index--) {
            ChatMessage candidate = active.get(index);
            if (candidate.role() == MessageRole.USER) {
                return candidate;
            }
        }
        return null;
    }

    /** 다시 생성할 대화의 에이전트를 정한다. 저장된 질문이 스킬 커맨드이면 보낼 때와 같게 판별한다. */
    private Routed routeExisting(
            CurrentUser user,
            Conversation conversation,
            List<ChatAttachment> attached,
            String question,
            Instant requestReceivedAt) {
        Agent agent = agents.requireById(conversation.agentId());
        if (agent.isDeleted()) {
            throw new ApiException(ErrorCode.AGENT_NOT_FOUND, "no such agent");
        }
        if (!agent.enabled()) {
            throw new ApiException(ErrorCode.AGENT_DISABLED, "this agent is disabled");
        }
        requireAttachmentOwner(user, agent, !attached.isEmpty());
        Flow flow = flows.find(agent.flow());
        return new Routed(
                conversation, agent, flow, attached, commandOf(agent, flow, question), requestReceivedAt, false);
    }

    private static void requireAttachmentOwner(CurrentUser user, Agent agent, boolean withAttachments) {
        if (!withAttachments) {
            return;
        }
        boolean privateAgent = agent.visibility() == AgentVisibility.PRIVATE;
        boolean sameOwner = Objects.equals(agent.ownerUserId(), user.id());
        if (!privateAgent || !sameOwner) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "this agent does not accept attachments");
        }
    }

    private void saveQuestion(
            CurrentUser user,
            Conversation conversation,
            String text,
            List<Long> attachmentIds,
            TurnIntent intent,
            Consumer<ChatEvent> onEvent) {
        if (intent instanceof TurnIntent.ProactiveCheck proactive) {
            if (!proactive.check().notifyStart()) {
                return;
            }
            // 질문 대신 시작 알림 줄 하나를 남긴다. 제목, 자동 turn 수, 대기 행은 건드리지 않는다.
            ChatMessage notice = transactions.execute(status -> messages.save(
                    ChatMessage.fromSystem(conversation.id(), proactive.check().startNotice(), clock.instant())));
            onEvent.accept(ChatEvent.system(conversation.publicId(), notice.id(), notice.content()));
            return;
        }
        // 다시 생성은 질문을 이미 저장했다. 자동 turn 의 알림 줄은 이 turn 을 열기 전에 저장했다.
        if (intent instanceof TurnIntent.Scheduled scheduled) {
            // 알림 줄, 지시, 자동 turn 수 초기화가 함께 남거나 함께 빠진다. 사람이 질문한 것과 같게 자동 turn 수를 새로 센다.
            List<ChatMessage> saved = transactions.execute(status -> {
                Instant savedAt = clock.instant();
                ChatMessage line =
                        messages.save(ChatMessage.fromSystem(conversation.id(), scheduled.notice(), savedAt));
                fillBlankTitle(conversation, text);
                ChatMessage question = messages.save(ChatMessage.fromUser(conversation.id(), user.id(), text, savedAt));
                conversationWriter.resetAutoTurns(conversation.id());
                return List.of(line, question);
            });
            ChatMessage line = saved.get(0);
            ChatMessage question = saved.get(1);
            onEvent.accept(ChatEvent.system(conversation.publicId(), line.id(), line.content()));
            onEvent.accept(ChatEvent.user(conversation.publicId(), question.id(), text));
            return;
        }
        if (!(intent instanceof TurnIntent.Fresh fresh)) {
            return;
        }
        List<Long> pendingIds = fresh.pendingIds();
        ChatMessage question = transactions.execute(status -> {
            fillBlankTitle(conversation, text);
            ChatMessage saved =
                    messages.save(ChatMessage.fromUser(conversation.id(), user.id(), text, clock.instant()));
            attachments.attach(saved.id(), conversation.id(), attachmentIds);
            // 사람이 질문했으니 사용자의 질문 없이 연 turn 의 수를 새로 센다.
            conversationWriter.resetAutoTurns(conversation.id());
            // 대기 행을 지우는 것과 그 글을 사용자 메시지로 남기는 것은 함께 남거나 함께 빠진다.
            // 지운 수가 읽은 수와 다르면 읽은 뒤 취소된 행이 있다. 취소한 글을 보내지 않게 되돌린다.
            if (!pendingIds.isEmpty() && pendingMessages.deleteAllByIdIn(pendingIds) != pendingIds.size()) {
                throw new PendingQueueChangedException();
            }
            return saved;
        });
        if (!pendingIds.isEmpty()) {
            onEvent.accept(ChatEvent.user(conversation.publicId(), question.id(), text));
            onEvent.accept(ChatEvent.pending(conversation.publicId()));
        }
        markCheckReportsRead(user, conversation);
    }

    private ChatMessage answerMessage(PendingTurn pending, String answer, Long executionId) {
        if (pending.intent() instanceof TurnIntent.Regenerate regenerate && regenerate.previousAnswer() != null) {
            return ChatMessage.regeneratedAnswer(
                    pending.conversation().id(),
                    answer,
                    executionId,
                    regenerate.previousAnswer().id(),
                    clock.instant());
        }
        return ChatMessage.fromAssistant(pending.conversation().id(), answer, executionId, clock.instant());
    }

    public void stop(CurrentUser user, Long executionId) {
        TurnHandle handle = turns.find(executionId).orElseGet(() -> {
            AgentExecution execution = executionRepository
                    .findById(executionId)
                    .orElseThrow(() -> new ApiException(ErrorCode.EXECUTION_NOT_FOUND, "execution not found"));
            if (!execution.userId().equals(user.id())) {
                throw new ApiException(ErrorCode.EXECUTION_NOT_FOUND, "execution not found");
            }
            throw new ApiException(ErrorCode.EXECUTION_NOT_RUNNING, "execution is not running");
        });
        if (!handle.userId().equals(user.id())) {
            throw new ApiException(ErrorCode.EXECUTION_NOT_FOUND, "execution not found");
        }
        turns.cancel(handle);
        boolean hadRuns = turns.hasRuns(handle);
        for (TurnRunRef run : turns.pendingStops(handle)) {
            turns.stopRun(run);
        }
        for (AgentExecution child : executionRepository.findByRootExecutionId(executionId)) {
            if (child.status() != ExecutionStatus.RUNNING || child.hermesRunId() == null) {
                continue;
            }
            Optional<Agent> childAgent = agents.findById(child.agentId());
            if (childAgent.isEmpty()) {
                // 루트 turn 의 중지는 이미 켰다. 에이전트 행이 없는 자식 하나 때문에 오류로 끝내지 않는다.
                log.warn("에이전트 행이 없어 자식 run 을 함께 멈추지 못했다 executionId={} agentId={}", child.id(), child.agentId());
                continue;
            }
            turns.trackRun(executionId, childAgent.get().apiBaseUrl(), child.profileName(), child.hermesRunId());
        }
        boolean firstStopSent = hadRuns || turns.awaitFirstStop(handle);
        if (!firstStopSent && turns.isFinished(handle)) {
            throw new ApiException(ErrorCode.EXECUTION_NOT_RUNNING, "execution is not running");
        }
        // 시도마다의 성패가 아니라 끝난 뒤의 상태로 정한다.
        // 제출과 이 요청이 같은 run 에 함께 보내 한쪽만 받아들여져도 그 run 은 멈췄다.
        // 시도 결과로 정하면 먼저 실패한 쪽 때문에 멈춘 run 을 두고 HERMES_UNAVAILABLE 로 답한다.
        // 멈춘 run 은 끝나면 목록에서 빠지므로 목록이 비었다고 실패로 보지 않는다.
        boolean stopped = firstStopSent || turns.hasStoppedRuns(handle);
        boolean failed = !stopped || !turns.pendingStops(handle).isEmpty();
        if (failed) {
            if (!turns.hasStoppedRuns(handle)) {
                turns.resume(handle);
            }
            throw new ApiException(ErrorCode.HERMES_UNAVAILABLE, "could not stop every Hermes run");
        }
        turns.confirmStop(handle);
    }

    /**
     * 이 실행들 중 자식을 가진 것을 낸다.
     *
     * <p>대화 이력이 「이 답이 어떻게 만들어졌는지 보기」 를 어느 답에 붙일지 정하는 데 쓴다. 실행마다
     * 세지 않고 한 번에 읽는다. 빈 {@code in} 절은 데이터베이스마다 다르게 동작하므로 목록이 비면
     * 부르지 않는다.
     */
    public Set<Long> executionIdsHavingChildren(List<ChatMessage> history) {
        List<Long> executionIds = history.stream()
                .map(ChatMessage::executionId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (executionIds.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(executions.idsHavingChildren(executionIds));
    }

    /** 답 메시지의 실행 상태를 한 번에 읽는다. */
    public Map<Long, ExecutionStatus> statuses(List<ChatMessage> history) {
        List<Long> ids = history.stream()
                .map(ChatMessage::executionId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return executionRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(AgentExecution::id, AgentExecution::status));
    }

    /**
     * 이 답들 중 막혀서 넘어간 것에 넘어간 곳의 provider 와 모델을 붙인다.
     *
     * <p>사건을 실행마다 세지 않고 한 번에 읽는다. 빈 {@code in} 절은 데이터베이스마다 다르게
     * 동작하므로 목록이 비면 부르지 않는다.
     *
     * <p>넘어간 곳의 모델은 내부 값이라 {@code ADMIN} 역할이 아니면 빈 묶음을 돌려준다(ADR-063).
     */
    public Map<Long, String> switchedLabels(CurrentUser viewer, List<ChatMessage> history) {
        if (!InternalValuePolicy.visibleTo(viewer)) {
            return Map.of();
        }
        List<Long> executionIds = history.stream()
                .map(ChatMessage::executionId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (executionIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> labels = new HashMap<>();
        for (ExecutionEvent event : executionEvents.findByExecutionIdInOrderByExecutionIdAscSequenceAsc(executionIds)) {
            if (event.eventType() == ExecutionEventType.PROVIDER_SWITCHED && event.detail() != null) {
                labels.put(event.executionId(), event.detail());
            }
        }
        return labels;
    }

    /** 답마다 도구와 하위 에이전트 사건을 한 번에 읽어 작업 과정 요약을 만든다. */
    public Map<Long, ActivitySummary> activitySummaries(List<ChatMessage> history) {
        List<Long> rootIds = history.stream()
                .map(ChatMessage::executionId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (rootIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, AgentExecution> roots = executionRepository.findAllById(rootIds).stream()
                .collect(Collectors.toMap(AgentExecution::id, it -> it));
        List<AgentExecution> descendants = executionRepository.findByRootExecutionIdIn(rootIds);
        Map<Long, Long> rootByExecution = new HashMap<>();
        rootIds.forEach(id -> rootByExecution.put(id, id));
        descendants.forEach(it -> rootByExecution.put(it.id(), it.rootExecutionId()));
        Map<Long, List<ExecutionEvent>> eventsByExecution =
                executionEvents.findByExecutionIdInOrderByExecutionIdAscSequenceAsc(rootByExecution.keySet()).stream()
                        .collect(Collectors.groupingBy(ExecutionEvent::executionId));
        Map<Long, ActivitySummary> summaries = new HashMap<>();
        for (Long rootId : rootIds) {
            AgentExecution root = roots.get(rootId);
            if (root == null) {
                continue;
            }
            List<AgentExecution> tree = new ArrayList<>();
            tree.add(root);
            descendants.stream()
                    .filter(it -> rootId.equals(it.rootExecutionId()))
                    .forEach(tree::add);
            int toolCount = 0;
            int subagentCount = 0;
            Instant latestFinish = null;
            for (AgentExecution execution : tree) {
                List<ExecutionEvent> events = eventsByExecution.getOrDefault(execution.id(), List.of());
                int toolStarts = 0;
                int toolCompletes = 0;
                int subagentStarts = 0;
                for (ExecutionEvent event : events) {
                    switch (event.eventType()) {
                        case TOOL_STARTED -> toolStarts++;
                        case TOOL_COMPLETED -> toolCompletes++;
                        case SUBAGENT_STARTED -> subagentStarts++;
                        default -> {}
                    }
                }
                toolCount += Math.max(toolStarts, toolCompletes);
                subagentCount += subagentStarts;
                if (!rootId.equals(execution.id()) && !events.isEmpty()) {
                    subagentCount++;
                }
                if (execution.finishedAt() != null
                        && (latestFinish == null || execution.finishedAt().isAfter(latestFinish))) {
                    latestFinish = execution.finishedAt();
                }
            }
            if (toolCount + subagentCount > 0) {
                Long durationMs = latestFinish == null
                        ? null
                        : Duration.between(root.startedAt(), latestFinish).toMillis();
                summaries.put(rootId, new ActivitySummary(toolCount, subagentCount, durationMs));
            }
        }
        return summaries;
    }

    /**
     * 이 대화의 첨부를 메시지 번호로 나눈다. 아직 메시지에 묶이지 않은 것은 뺀다.
     *
     * <p>메시지마다 묻지 않고 한 번에 읽는다. 지워진 첨부도 담아 지난 대화에 자리를 남긴다. 부르는
     * 순서에 기대지 않도록 여기서도 대화 주인을 확인한다.
     */
    public Map<Long, List<ChatAttachment>> attachmentsByMessage(CurrentUser user, Long conversationId) {
        Conversation conversation = access.requireOwn(user, conversationId);
        return attachments.allOf(conversation.id()).stream()
                .filter(it -> it.messageId() != null)
                .collect(Collectors.groupingBy(ChatAttachment::messageId));
    }

    /**
     * 답 메시지마다 그 turn 이 만든 결과물을 한 번에 읽는다. 사용자 메시지는 결과물이 없어 묻지 않는다.
     *
     * <p>{@link #history} 로 주인을 확인한 메시지 목록을 받는다.
     */
    public Map<Long, List<ChatArtifact>> artifactsByMessage(List<ChatMessage> history) {
        return artifacts.byMessage(history.stream()
                .filter(message -> message.role() == MessageRole.ASSISTANT)
                .map(ChatMessage::id)
                .toList());
    }

    /** 주인을 확인하고 메시지를 읽는다. 점검 대화면 그 대화의 열지 않은 보고를 연 것으로 적는다. */
    public List<ChatMessage> history(CurrentUser user, Long conversationId) {
        Conversation conversation = access.requireOwn(user, conversationId);
        markCheckReportsRead(user, conversation);
        return messages.findByConversationIdOrderByIdAsc(conversation.id());
    }

    /** 사용자가 점검 대화를 읽었거나 그 대화에 질문을 남겼다. 보고 열람 기록은 대화를 막지 않으므로 실패해도 넘어간다. */
    private void markCheckReportsRead(CurrentUser user, Conversation conversation) {
        if (conversation.purpose() != ConversationPurpose.CHECK) {
            return;
        }
        for (CheckReportReads reads : checkReportReads) {
            try {
                reads.markRead(user.id(), conversation.id(), clock.instant());
            } catch (RuntimeException ex) {
                log.warn("점검 대화의 보고를 연 것으로 적지 못했다 conversationId={}", conversation.id(), ex);
            }
        }
    }

    /**
     * 대화에 지금 도는 turn 을 알려 준다.
     *
     * <p>주인 확인을 표시보다 먼저 한다. 남의 대화에 도는 turn 이 있는지 새지 않게 하려는 것이다.
     * 도는지는 실행 줄의 상태가 아니라 메모리 표시로 본다. 흐름은 루트 줄이 끝난 뒤에도 자식이 돈다.
     */
    @Transactional(readOnly = true)
    public RunningTurn running(CurrentUser user, Long conversationId) {
        Conversation conversation = access.requireOwn(user, conversationId);
        TurnMark mark = turns.markOf(conversation.id());
        if (!mark.running()) {
            return new RunningTurn(false, null, null);
        }
        if (mark.executionId() == null) {
            return new RunningTurn(true, null, null);
        }
        Instant startedAt = executionRepository
                .findById(mark.executionId())
                .map(AgentExecution::startedAt)
                .orElse(null);
        return new RunningTurn(true, mark.executionId(), startedAt);
    }

    /**
     * 사용자의 대화를 최근에 바뀐 것부터 한 쪽 읽는다.
     *
     * @param cursor 앞 쪽이 돌려준 {@code nextCursor}. 처음이면 null
     * @param limit 한 쪽의 최대 개수. {@link #MAX_CONVERSATION_PAGE} 를 넘으면 그 값으로 줄인다
     */
    public ConversationPage conversationsOf(CurrentUser user, String cursor, int limit) {
        int size = Math.clamp(limit, 1, MAX_CONVERSATION_PAGE);
        // 한 줄을 더 읽어 다음 쪽이 있는지 안다. 개수를 한 쪽에 딱 맞게 읽으면 마지막 쪽에서도 빈 쪽을 한 번 더 부르게 된다.
        PageRequest window = PageRequest.ofSize(size + 1);
        List<Conversation> rows;
        if (cursor == null) {
            rows = conversations.findByUserIdAndDeletedAtIsNullOrderByUpdatedAtDescIdDesc(user.id(), window);
        } else {
            ConversationCursor from = ConversationCursor.decode(cursor);
            rows = conversations.findPageAfter(user.id(), from.updatedAt(), from.id(), window);
        }
        if (rows.size() <= size) {
            return new ConversationPage(rows, null);
        }
        List<Conversation> items = rows.subList(0, size);
        return new ConversationPage(
                items, ConversationCursor.of(items.getLast()).encode());
    }

    /** 사용자의 대화 한 줄을 읽는다. 없거나 남의 것이면 같은 응답으로 숨긴다. */
    public Conversation conversationOf(CurrentUser user, UUID publicId) {
        return access.requireOwn(user, publicId);
    }

    @Transactional
    public Conversation rename(CurrentUser user, Long conversationId, String title) {
        String normalized = Conversation.normalizedTitle(title);
        access.requireOwn(user, conversationId);
        if (conversationWriter.renameIfActive(conversationId, user.id(), normalized, clock.instant()) == 0) {
            throw new ApiException(ErrorCode.CONVERSATION_NOT_FOUND, "this conversation does not exist");
        }
        return access.requireOwn(user, conversationId);
    }

    /**
     * 대화에서 쓸 모델과 effort 를 바꾼다. 그 뒤의 실행이 이 값을 쓴다.
     *
     * <p>고른 모델이 Hermes 목록에 있는지는 보지 않는다. 그룹이 숨긴 모델은 {@code MODEL_HIDDEN} 으로 거절한다. effort
     * {@code none} 은 그 에이전트의 목록에서 끄기 지원이 확인된 모델에서만 받고, 아니면 {@code VALIDATION_FAILED} 다. 대화 목록의 순서는 주고받은 시각으로 정하므로
     * {@code updatedAt} 을 건드리지 않는다.
     *
     * @param choice 요청에서 {@link ModelChoice#of} 로 검증해 만든 선택
     */
    @Transactional
    public Conversation chooseModel(CurrentUser user, Long conversationId, ModelChoice choice) {
        Conversation conversation = access.requireOwn(user, conversationId);
        modelTiers.requireVisible(user, choice);
        // none 일 때만 에이전트를 얻는다. 꺼진 에이전트나 에이전트가 없는 대화의 다른 effort 저장은 그대로 둔다.
        if (ModelChoice.EFFORT_NONE.equals(choice.reasoningEffort())) {
            Agent agent = agents.requireStartable(
                    user, agents.requireById(conversation.agentId()).code());
            modelTiers.requireEffortAllowed(user, agent, choice);
        }
        if (conversations.chooseModelIfActive(
                        conversationId,
                        user.id(),
                        choice.provider(),
                        choice.model(),
                        choice.reasoningEffort(),
                        ModelSelectionMode.CUSTOM)
                == 0) {
            throw new ApiException(ErrorCode.CONVERSATION_NOT_FOUND, "this conversation does not exist");
        }
        return access.requireOwn(user, conversationId);
    }

    /** 대화가 고른 단계를 저장한다. DEFAULT 는 에이전트 기본 모델만 쓰도록 사용자·그룹 기본값도 건너뛴다. */
    @Transactional
    public Conversation chooseModelTier(
            CurrentUser user, Long conversationId, ModelSelectionMode mode, ModelTier tier) {
        Conversation conversation = access.requireOwn(user, conversationId);
        if (mode == null
                || mode == ModelSelectionMode.CUSTOM
                || (mode == ModelSelectionMode.TIER && tier == null)
                || (mode == ModelSelectionMode.DEFAULT && tier != null)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "invalid model tier selection");
        }
        if (mode == ModelSelectionMode.TIER) {
            Agent agent = agents.requireStartable(
                    user, agents.requireById(conversation.agentId()).code());
            modelTiers.resolveTier(user, tier, agent);
        }
        if (conversations.chooseModelTierIfActive(conversationId, user.id(), mode, tier) == 0) {
            throw new ApiException(ErrorCode.CONVERSATION_NOT_FOUND, "this conversation does not exist");
        }
        return access.requireOwn(user, conversationId);
    }

    @Transactional
    public void delete(CurrentUser user, Long conversationId) {
        access.requireOwn(user, conversationId);
        if (conversationWriter.deleteIfActive(conversationId, user.id(), clock.instant()) == 0) {
            throw new ApiException(ErrorCode.CONVERSATION_NOT_FOUND, "this conversation does not exist");
        }
        // 지운 대화에는 더 보낼 수 없다. 남기면 기동 확인이 보낼 수 없는 행을 계속 만난다.
        pendingMessages.deleteAllOf(conversationId);
    }

    /**
     * 메시지 없이 제목이 빈 대화를 만든다.
     *
     * <p>사진을 먼저 올리거나 첫 메시지 전에 모델을 고르려면 대화가 먼저 있어야 한다. 모델은 흐름이 붙은
     * 에이전트에서도 고르므로 에이전트를 쓸 수 있는지만 본다. 사진을 받지 않는 에이전트는 보낼 때 거절한다.
     * 제목은 첫 메시지가 정한다.
     */
    public Conversation startEmpty(CurrentUser user, String agentCode) {
        Agent agent = agents.requireStartable(user, agentCode);
        return conversations.save(Conversation.startedBy(user.id(), "", agent.id(), clock.instant()));
    }

    /**
     * 예약 작업이 결과를 남길 빈 대화를 만든다(ADR-078). 제목은 작업 이름이고 메시지는 첫 turn 이 남긴다.
     *
     * <p>주인과 에이전트를 쓸 수 있는지는 부르는 쪽이 이미 확인했다. 부르는 쪽의 트랜잭션이 있으면 그 안에서 저장한다.
     */
    public Conversation startForTask(Long ownerUserId, Long agentId, String title, Long taskId) {
        return conversations.save(Conversation.startedForTask(ownerUserId, title, agentId, taskId, clock.instant()));
    }

    private static String titleFrom(String text) {
        String single = text.strip().replaceAll("\\s+", " ");
        return single.length() <= TITLE_LIMIT ? single : single.substring(0, TITLE_LIMIT);
    }

    private static String hermesStatus(HermesRunResult result) {
        return result.status() == null ? "UNKNOWN" : result.status().toUpperCase();
    }

    /** 스트림으로 온 사건을 화면으로 중계하면서 우리 이름으로도 옮겨 적는다. */
    private void forward(PendingTurn pending, RunEvent event, Consumer<ChatEvent> onEvent) {
        append(pending, event);
        String type = event.type() == null ? "" : event.type().toLowerCase();
        CheckTurn check = pending.intent() instanceof TurnIntent.ProactiveCheck proactive ? proactive.check() : null;
        if ("message.delta".equals(type) && event.text() != null) {
            executions.markFirstDelta(pending.execution());
            synchronized (pending) {
                pending.streamed().append(event.text());
                // 살펴보기의 답은 결과 블록의 JSON 이 섞인 글이라 흘리지 않는다. 검사해 그린 글만 남긴다.
                if (check == null) {
                    onEvent.accept(ChatEvent.delta(event.text()));
                }
            }
        } else if ("tool.started".equals(type)) {
            onEvent.accept(ChatEvent.tool(event.toolName(), event.detail(), ChatEvent.STARTED, null, null));
            if (check != null) {
                check.toolStarted(pending.execution().id());
            }
        } else if ("tool.completed".equals(type)) {
            onEvent.accept(ChatEvent.tool(
                    event.toolName(), event.detail(), ChatEvent.COMPLETED, event.durationMs(), event.failed()));
        } else if ("subagent.start".equals(type)) {
            onEvent.accept(ChatEvent.subagent(
                    event.subagentId(),
                    event.goal() == null ? event.detail() : event.goal(),
                    event.model(),
                    ChatEvent.STARTED,
                    null,
                    null,
                    null,
                    null));
        } else if ("subagent.complete".equals(type)) {
            onEvent.accept(ChatEvent.subagent(
                    event.subagentId(),
                    event.goal() == null ? event.detail() : event.goal(),
                    event.model(),
                    ChatEvent.COMPLETED,
                    event.inputTokens(),
                    event.outputTokens(),
                    event.durationMs(),
                    event.failed()));
        }
    }

    private void append(PendingTurn pending, ExecutionEventType type, String detail) {
        store(pending, sequence -> eventRecorder.record(pending.execution(), type, detail, sequence));
    }

    private void append(PendingTurn pending, RunEvent event) {
        store(pending, sequence -> eventRecorder.record(pending.execution(), event, sequence));
    }

    /**
     * 사건 하나를 저장한다.
     *
     * <p>저장이 실패해도 중계와 대화는 그대로 이어진다. 사건은 관측용이고 그것 때문에 답이 끊기면 안
     * 된다. 옮겨 적지 못한 사건과 저장에 실패한 사건은 순서를 소비하지 않아, 번호가 1부터 빈틈없이
     * 이어진다.
     */
    private void store(PendingTurn pending, IntFunction<ExecutionEvent> build) {
        try {
            ExecutionEvent event = build.apply(pending.counter().peek());
            if (event == null) {
                return;
            }
            executionEvents.save(event);
            pending.counter().advance();
        } catch (RuntimeException ex) {
            log.warn(
                    "could not record an execution event executionId={}",
                    pending.execution().id(),
                    ex);
        }
    }

    /** 실행 하나 안에서 사건 순서를 1부터 센다. 스트림을 읽는 스레드가 하나라 잠금이 필요 없다. */
    private static final class SequenceCounter {
        private int next = 1;

        int peek() {
            return next;
        }

        void advance() {
            next++;
        }
    }

    private record PendingTurn(
            CurrentUser user,
            Conversation conversation,
            Agent agent,
            HermesRunCommand command,
            AgentExecution execution,
            SequenceCounter counter,
            StringBuilder streamed,
            TurnIntent intent,
            Consumer<ChatEvent> onEvent) {}

    /**
     * 이 turn 이 어느 대화와 에이전트의 것인지, 그리고 어느 흐름으로 갈지. 흐름이 없으면 null 이다.
     * {@code attached} 는 판정을 통과해 이 메시지에 묶을 첨부이고 없으면 빈 목록이다.
     * {@code command} 는 이름을 확인한 스킬 커맨드이고 커맨드가 아니면 null 이다.
     * {@code created} 는 이 요청이 새로 만든 대화인지다.
     */
    private record Routed(
            Conversation conversation,
            Agent agent,
            Flow flow,
            List<ChatAttachment> attached,
            SkillCommand command,
            Instant requestReceivedAt,
            boolean created) {}
}
