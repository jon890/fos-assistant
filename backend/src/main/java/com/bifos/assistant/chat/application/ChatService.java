package com.bifos.assistant.chat.application;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.application.StarterSuggestionService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.chat.domain.ChatArtifact;
import com.bifos.assistant.chat.domain.ChatAttachment;
import com.bifos.assistant.chat.domain.ChatMessage;
import com.bifos.assistant.chat.domain.ChatPendingMessage;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.domain.MessageRole;
import com.bifos.assistant.chat.domain.ModelChoice;
import com.bifos.assistant.chat.domain.type.ModelSelectionMode;
import com.bifos.assistant.chat.domain.type.ModelTier;
import com.bifos.assistant.chat.infra.ArtifactStore;
import com.bifos.assistant.chat.infra.ChatMessageRepository;
import com.bifos.assistant.chat.infra.ChatPendingMessageRepository;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.context.AssembledContext;
import com.bifos.assistant.context.ContextAssembler;
import com.bifos.assistant.hermes.HermesRunEventStream;
import com.bifos.assistant.hermes.HermesRunsClient;
import com.bifos.assistant.hermes.dto.HermesRunCommand;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.hermes.dto.RunEvent;
import com.bifos.assistant.memory.application.MemoryProposer;
import com.bifos.assistant.orchestration.application.Flow;
import com.bifos.assistant.orchestration.application.FlowRegistry;
import com.bifos.assistant.orchestration.domain.RunSession;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.shared.util.ExternalData;
import com.bifos.assistant.skill.application.SkillCommandCatalog;
import com.bifos.assistant.skill.application.SkillUseRecorder;
import com.bifos.assistant.usage.application.ExecutionContextSnapshot;
import com.bifos.assistant.usage.application.ExecutionEventRecorder;
import com.bifos.assistant.usage.application.ExecutionRecorder;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.ExecutionEvent;
import com.bifos.assistant.usage.domain.ExecutionEventType;
import com.bifos.assistant.usage.domain.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.infra.ExecutionEventRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
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
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ChatService {
    private static final int TITLE_LIMIT = 60;

    /** 대화 목록 한 쪽의 상한이다. 웹이 더 크게 요청해도 이만큼만 읽는다. */
    public static final int MAX_CONVERSATION_PAGE = 100;

    private final ConversationRepository conversations;
    private final ConversationSessions sessions;
    private final ConversationAccess access;
    private final ChatMessageRepository messages;
    private final AgentService agents;
    private final HermesRunsClient hermes;
    private final HermesRunEventStream eventStream;
    private final ExecutionRecorder executions;
    private final ExecutionEventRecorder eventRecorder;
    private final ExecutionEventRepository executionEvents;
    private final AgentExecutionRepository executionRepository;
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
        TurnCancellation.TurnHandle handle =
                turns.open(user.id(), routed.conversation().id());
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
     * 아직 전하지 않은 끝난 위임 결과를 모아 사용자의 질문 없이 turn 하나를 돌린다(ADR-040).
     *
     * <p>부르는 쪽이 그 대화의 turn 잠금을 이미 잡았다. 잠금을 잡기 전에 읽은 목록은 다른 자동 turn 이 이미 전했을 수
     * 있어 여기서 다시 읽는다. 비었으면 아무것도 남기지 않고 돌아간다.
     *
     * <p>알림 줄 저장, 결과마다 전했다는 표시, 자동 turn 수 증가는 한 트랜잭션이다. 그 뒤 Hermes 가 실패해도 같은
     * 결과로 다시 깨우지 않는다. 같은 실패를 되풀이하지 않기 위해서다. 실패는 예외로 올라간다.
     *
     * @param owner 대화 주인. 요청이 없으므로 부르는 쪽이 사용자 행으로 만든다
     * @param onEvent 알림 줄, {@code started}, 답 조각, {@code done} 이나 {@code stopped} 를 받는다
     */
    public void runDelegationResults(
            CurrentUser owner, Long conversationId, TurnCancellation.TurnHandle handle, Consumer<ChatEvent> onEvent) {
        List<AgentExecution> results = executionRepository.findUndeliveredResults(conversationId);
        if (results.isEmpty()) {
            return;
        }
        Map<Long, Agent> resultAgents =
                agents.byIds(results.stream().map(AgentExecution::agentId).toList());
        String input = delegationInput(results, resultAgents);
        Routed routed = route(owner, conversationId, input, null, List.of());
        if (routed.flow() != null) {
            // 흐름은 이 입력을 받을 자리가 없다. 깨우는 쪽이 이미 거르므로 그 사이 흐름이 붙은 경우뿐이다.
            // 깨우는 쪽이 거르는 것과 별개로 남긴다. 거르기와 잠금 사이에 에이전트의 흐름이 바뀌어도 흐름에 이 입력을 보내지 않는다.
            log.warn("흐름이 붙은 대화라 맡긴 일의 결과를 전하지 않는다 conversationId={}", conversationId);
            return;
        }
        List<Long> ids = results.stream().map(AgentExecution::id).toList();
        TurnIntent intent = new TurnIntent.DelegationResults(ids, delegationNotice(results, resultAgents));
        ChatTurn turn = runTurn(owner, routed, input, intent, onEvent, true, handle);
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
            CurrentUser owner, Long conversationId, TurnCancellation.TurnHandle handle, Consumer<ChatEvent> onEvent) {
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
    private void markStoppedAndHoldPending(TurnCancellation.TurnHandle handle, Long conversationId) {
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
            TurnCancellation.TurnHandle handle,
            PendingTurn pending,
            HermesRunResult result,
            ModelChoice choice,
            Instant startedAt) {
        try {
            return recorded(cancel(pending, result, choice), startedAt);
        } finally {
            markStoppedAndHoldPending(handle, pending.conversation().id());
        }
    }

    /**
     * 중지가 확정된 turn 이 예외로 끝날 때 실행 줄을 취소로 남기고 대기 줄을 멈춘다.
     *
     * <p>사용자가 중지를 확정한 실행은 {@code RUNNING} 으로 남지 않는다. 예외가 실행 줄을 이미 {@code FAILED} 로 적은
     * 뒤라면 그대로 둔다. 취소 기록이 실패해도 대기 줄은 멈추고, 어느 쪽 실패도 올리지 않아 원래 예외가 그대로 올라간다.
     */
    private void cancelAndHoldIfStopConfirmed(
            TurnCancellation.TurnHandle handle, PendingTurn pending, ModelChoice choice) {
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
            markStoppedAndHoldPending(handle, pending.conversation().id());
        }
    }

    /**
     * 중지로 끝난 흐름 turn 의 결과물을 묶은 뒤 대기 줄을 멈춘다.
     *
     * <p>흐름이 취소를 이미 기록했다. 결과물 묶기가 던져도 잠금을 풀기 전에 대기 줄을 멈춘다.
     */
    private void recordStoppedFlow(TurnCancellation.TurnHandle handle, ChatTurn turn, Instant startedAt) {
        try {
            recorded(turn, startedAt);
        } finally {
            markStoppedAndHoldPending(handle, turn.conversationId());
        }
    }

    /**
     * 중지가 확정된 흐름 turn 이 예외로 끝날 때 뿌리 실행 줄을 취소로 남기고 대기 줄을 멈춘다.
     *
     * <p>흐름이 뿌리 실행을 이미 끝난 상태로 적었으면 그대로 둔다. 실행 줄을 다시 읽어 본다. 흐름이 들고 있는 객체의
     * 상태를 여기서는 알 수 없다. {@code rootExecutionId} 가 null 이면 실행 줄을 만들기 전에 끝난 것이다.
     */
    private void cancelFlowAndHoldIfStopConfirmed(
            TurnCancellation.TurnHandle handle, Long conversationId, Long rootExecutionId) {
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
     * 결과마다 에이전트 이름, 실행 번호, 상태를 적은 머리줄을 두고, 답이 있으면 그 아래에 잇는다. 실패는 오류 코드를
     * 머리줄에 더한다.
     *
     * <p>연결용 에이전트의 답은 외부 서비스의 글을 담으므로 {@code <external-data>} 로 감싸 지시가 아니라고 알린다.
     * 에이전트 행이 없는 결과도 출처를 모르므로 감싼다. 감싸도 모델이 그 글을 따르지 않는다는 보장은 없다(ADR-049).
     */
    private static String delegationInput(List<AgentExecution> results, Map<Long, Agent> resultAgents) {
        StringBuilder input = new StringBuilder("맡긴 일의 결과가 도착했다.");
        for (AgentExecution result : results) {
            input.append("\n\n[에이전트: ")
                    .append(agentName(result, resultAgents))
                    .append(", 실행 번호: ")
                    .append(result.id())
                    .append(", 상태: ")
                    .append(result.status().name());
            if (result.status() == ExecutionStatus.FAILED) {
                input.append(", 오류: ").append(result.errorCode());
            }
            input.append(']');
            if (result.outputText() != null && !result.outputText().isBlank()) {
                input.append('\n')
                        .append(
                                isExternalResult(result, resultAgents)
                                        ? ExternalData.wrap(result.outputText())
                                        : result.outputText());
            }
        }
        return input.toString();
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
            TurnCancellation.TurnHandle existingHandle) {
        Instant requestReceivedAt = routed.requestReceivedAt();
        Conversation conversation = routed.conversation();
        List<Long> attachmentIds =
                routed.attached().stream().map(ChatAttachment::id).toList();
        TurnCancellation.TurnHandle handle =
                existingHandle == null ? turns.open(user.id(), conversation.id()) : existingHandle;
        boolean closesHandle = existingHandle == null;
        try {
            saveQuestion(user, conversation, text, attachmentIds, intent, onEvent);
            // 폴더를 만들기 전에 잡는다. 이 시각 뒤에 바뀐 HTML 이 이 turn 의 결과물이다.
            Instant startedAt = Instant.now();
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
                    context.chars(), null, context.instructionsHash(), context.omittedItems());

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
                        requestReceivedAt);
                String code = ex instanceof ApiException api ? api.code().name() : "MODEL_TIER_RESOLVE_FAILED";
                executions.fail(failed.execution(), code);
                append(failed, ExecutionEventType.RUN_FAILED, code);
                throw ex;
            }
            ModelChoice choice = resolved.choice();
            PendingTurn pending =
                    begin(user, routed, input, context, snapshot, choice, resolved.tier(), intent, requestReceivedAt);
            if (command != null) {
                skillUses.recordCommand(pending.execution().id(), command.name());
            }
            turns.rekey(handle, pending.execution().id());
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
                if (streaming) {
                    relay(pending, runId, handle, onEvent);
                }
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
                executions.fail(pending.execution(), ErrorCode.PROVIDER_BLOCKED.name());
                append(pending, ExecutionEventType.RUN_FAILED, ErrorCode.PROVIDER_BLOCKED.name());
                throw new ApiException(ErrorCode.PROVIDER_BLOCKED, "every account of the chosen provider is blocked");
            }
            executions.fail(pending.execution(), hermesStatus(result));
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
            TurnCancellation.TurnHandle existingHandle) {
        Conversation conversation = routed.conversation();
        TurnCancellation.TurnHandle handle =
                existingHandle == null ? turns.open(user.id(), conversation.id()) : existingHandle;
        boolean closesHandle = existingHandle == null;
        try {
            if (intent instanceof TurnIntent.Fresh) {
                fillBlankTitle(conversation, text);
            }
            Instant startedAt = Instant.now();
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
        Flow flow = flows.find(agent.flow());
        SkillCommand command = commandOf(agent, flow, text);
        Conversation conversation = existing != null
                ? existing
                : conversations.save(Conversation.startedBy(user.id(), titleFrom(text), agent.id()));
        List<ChatAttachment> attached = attachments.requireAttachable(conversation.id(), attachmentIds);
        return new Routed(conversation, agent, flow, attached, command, requestReceivedAt);
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
            conversations.fillTitleIfBlank(conversation.id(), title);
            conversation.titleIfBlank(title);
        }
    }

    /**
     * 대화가 고른 값으로 명령과 실행 줄을 만든다. 사용자 메시지는 이미 저장돼 있다.
     *
     * <p>고르지 않은 provider, 모델, effort 는 null 그대로 실어 profile 의 기본값으로 돌게 둔다.
     *
     * <p>보낼 session 을 명령보다 먼저 정한다. 실행 줄에는 그 대화의 뿌리 session 을 적는다. 압축 교체 뒤에는
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
            Instant requestReceivedAt) {
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
                conversation,
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
        return new PendingTurn(
                user, conversation, agent, command, execution, new SequenceCounter(), new StringBuilder(), intent);
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

    private void relay(
            PendingTurn pending, String runId, TurnCancellation.TurnHandle handle, Consumer<ChatEvent> onEvent) {
        // 일부 HTTP 스트림은 다른 스레드의 close 중에도 readLine 을 놓지 않는다.
        // 중지 유예 시간이 지나면 요청 스레드를 먼저 풀어 상태 조회와 stopped 사건으로 진행한다.
        CompletableFuture<Void> streamDone = new CompletableFuture<>();
        Thread.startVirtualThread(() -> {
            try {
                eventStream.open(
                        pending.command().apiBaseUrl(),
                        pending.command().profileName(),
                        runId,
                        event -> {
                            synchronized (pending) {
                                if (!handle.cancelled().get() || !turns.isStopConfirmed(handle)) {
                                    forward(pending, event, onEvent);
                                }
                            }
                        },
                        stream -> turns.attachStream(handle, stream),
                        pending.agent().connectorManaged());
            } catch (ApiException ex) {
                log.warn("Hermes event stream ended before final status runId={}", runId, ex);
            } finally {
                turns.detachStream(handle);
                streamDone.complete(null);
            }
        });
        turns.awaitStreamOrGrace(handle, streamDone);
    }

    private HermesRunResult awaitCompletion(PendingTurn pending, String runId) {
        try {
            return hermes.awaitCompletion(pending.command(), runId);
        } catch (ApiException ex) {
            executions.fail(pending.execution(), ex.code().name());
            append(pending, ExecutionEventType.RUN_FAILED, ex.code().name());
            throw ex;
        }
    }

    private ChatTurn finish(PendingTurn pending, HermesRunResult result, ModelChoice requested) {
        pending.conversation().rememberSession(result.sessionId());
        conversations.touchSession(
                pending.conversation().id(),
                result.sessionId() == null || result.sessionId().isBlank() ? null : result.sessionId(),
                Instant.now());

        AgentExecution execution = executions.complete(pending.execution(), pending.agent(), result, requested);
        append(pending, ExecutionEventType.RUN_COMPLETED, null);
        String answer = result.output() == null ? "" : result.output();
        ChatMessage message = messages.save(answerMessage(pending, answer, execution.id()));
        memoryProposer.proposeFrom(pending.user(), pending.conversation(), pending.agent(), execution, answer);
        starterSuggestions.refreshIfStale(pending.user(), pending.agent());
        return new ChatTurn(
                pending.conversation().id(),
                pending.conversation().publicId(),
                execution.id(),
                answer,
                message.id(),
                false);
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
        ChatMessage message = answer.isBlank() ? null : messages.save(answerMessage(pending, answer, execution.id()));
        if (result != null && result.sessionId() != null && !result.sessionId().isBlank()) {
            pending.conversation().rememberSession(result.sessionId());
            conversations.touchSession(pending.conversation().id(), result.sessionId(), Instant.now());
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
        TurnCancellation.TurnHandle handle = turns.open(user.id(), conversation.id());
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
        Flow flow = flows.find(agent.flow());
        return new Routed(conversation, agent, flow, attached, commandOf(agent, flow, question), requestReceivedAt);
    }

    private void saveQuestion(
            CurrentUser user,
            Conversation conversation,
            String text,
            List<Long> attachmentIds,
            TurnIntent intent,
            Consumer<ChatEvent> onEvent) {
        if (intent instanceof TurnIntent.Regenerate) {
            return;
        }
        if (intent instanceof TurnIntent.DelegationResults results) {
            // 알림 줄이 곧 전했다는 표시다. 셋이 함께 남거나 함께 빠진다. 제목은 채우지 않는다.
            ChatMessage notice = transactions.execute(status -> {
                ChatMessage saved = messages.save(ChatMessage.fromSystem(conversation.id(), results.notice()));
                Instant deliveredAt = Instant.now();
                results.executionIds().forEach(id -> executionRepository.markResultDelivered(id, deliveredAt));
                conversations.incrementAutoTurns(conversation.id());
                return saved;
            });
            onEvent.accept(ChatEvent.system(conversation.publicId(), notice.id(), results.notice()));
            return;
        }
        if (!(intent instanceof TurnIntent.Fresh fresh)) {
            return;
        }
        List<Long> pendingIds = fresh.pendingIds();
        ChatMessage question = transactions.execute(status -> {
            fillBlankTitle(conversation, text);
            ChatMessage saved = messages.save(ChatMessage.fromUser(conversation.id(), user.id(), text));
            attachments.attach(saved.id(), conversation.id(), attachmentIds);
            // 사람이 질문했으니 사용자의 질문 없이 연 turn 의 수를 새로 센다.
            conversations.resetAutoTurns(conversation.id());
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
    }

    private static ChatMessage answerMessage(PendingTurn pending, String answer, Long executionId) {
        if (pending.intent() instanceof TurnIntent.Regenerate regenerate && regenerate.previousAnswer() != null) {
            return ChatMessage.regeneratedAnswer(
                    pending.conversation().id(),
                    answer,
                    executionId,
                    regenerate.previousAnswer().id());
        }
        return ChatMessage.fromAssistant(pending.conversation().id(), answer, executionId);
    }

    public void stop(CurrentUser user, Long executionId) {
        TurnCancellation.TurnHandle handle = turns.find(executionId).orElseGet(() -> {
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
        for (TurnCancellation.RunRef run : turns.pendingStops(handle)) {
            turns.stopRun(run);
        }
        for (AgentExecution child : executionRepository.findByRootExecutionId(executionId)) {
            if (child.status() != ExecutionStatus.RUNNING || child.hermesRunId() == null) {
                continue;
            }
            Optional<Agent> childAgent = agents.findById(child.agentId());
            if (childAgent.isEmpty()) {
                // 뿌리 turn 의 중지는 이미 켰다. 에이전트 행이 없는 자식 하나 때문에 오류로 끝내지 않는다.
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
     */
    public Map<Long, String> switchedLabels(List<ChatMessage> history) {
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

    public List<ChatMessage> history(CurrentUser user, Long conversationId) {
        Conversation conversation = access.requireOwn(user, conversationId);
        return messages.findByConversationIdOrderByIdAsc(conversation.id());
    }

    /**
     * 대화에 지금 도는 turn 을 알려 준다.
     *
     * <p>주인 확인을 표시보다 먼저 한다. 남의 대화에 도는 turn 이 있는지 새지 않게 하려는 것이다.
     * 도는지는 실행 줄의 상태가 아니라 메모리 표시로 본다. 흐름은 뿌리 줄이 끝난 뒤에도 자식이 돈다.
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
        if (conversations.renameIfActive(conversationId, user.id(), normalized, Instant.now()) == 0) {
            throw new ApiException(ErrorCode.CONVERSATION_NOT_FOUND, "this conversation does not exist");
        }
        return access.requireOwn(user, conversationId);
    }

    /**
     * 대화에서 쓸 모델과 effort 를 바꾼다. 그 뒤의 실행이 이 값을 쓴다.
     *
     * <p>고른 모델이 Hermes 목록에 있는지는 보지 않는다. 대화 목록의 순서는 주고받은 시각으로 정하므로
     * {@code updatedAt} 을 건드리지 않는다.
     *
     * @param choice 요청에서 {@link ModelChoice#of} 로 검증해 만든 선택
     */
    @Transactional
    public Conversation chooseModel(CurrentUser user, Long conversationId, ModelChoice choice) {
        access.requireOwn(user, conversationId);
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

    /** 대화가 고른 단계를 저장한다. DEFAULT 는 profile 기본값만 쓰도록 사용자·그룹 기본값도 건너뛴다. */
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
        if (conversations.deleteIfActive(conversationId, user.id(), Instant.now()) == 0) {
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
        return conversations.save(Conversation.startedBy(user.id(), "", agent.id()));
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
        if ("message.delta".equals(type) && event.text() != null) {
            executions.markFirstDelta(pending.execution());
            synchronized (pending) {
                pending.streamed().append(event.text());
                onEvent.accept(ChatEvent.delta(event.text()));
            }
        } else if ("tool.started".equals(type)) {
            onEvent.accept(ChatEvent.tool(event.toolName(), event.detail(), ChatEvent.STARTED, null, null));
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
            TurnIntent intent) {}

    /**
     * 이 turn 이 어느 대화와 에이전트의 것인지, 그리고 어느 흐름으로 갈지. 흐름이 없으면 null 이다.
     * {@code attached} 는 판정을 통과해 이 메시지에 묶을 첨부이고 없으면 빈 목록이다.
     * {@code command} 는 이름을 확인한 스킬 커맨드이고 커맨드가 아니면 null 이다.
     */
    private record Routed(
            Conversation conversation,
            Agent agent,
            Flow flow,
            List<ChatAttachment> attached,
            SkillCommand command,
            Instant requestReceivedAt) {}
}
