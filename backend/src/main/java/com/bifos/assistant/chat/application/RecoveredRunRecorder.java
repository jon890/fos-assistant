package com.bifos.assistant.chat.application;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.chat.application.model.RecoveredRunKind;
import com.bifos.assistant.chat.domain.ChatMessage;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.domain.type.MessageRole;
import com.bifos.assistant.chat.infra.ChatMessageRepository;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.model.domain.ModelChoice;
import com.bifos.assistant.orchestration.application.DelegationFinished;
import com.bifos.assistant.orchestration.application.DelegationOutput;
import com.bifos.assistant.orchestration.application.FlowRegistry;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.application.ExecutionEventRecorder;
import com.bifos.assistant.usage.application.ExecutionRecorder;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.type.ExecutionEventType;
import com.bifos.assistant.usage.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.infra.ExecutionEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 기동할 때 {@code RUNNING} 으로 남은 실행 줄 하나에 Hermes 의 답을 적는다(ADR-061).
 *
 * <p>실행 줄은 보통 turn 과 같은 기록 경로({@link ExecutionRecorder})로 적는다. 종류마다 함께 적는 것이 다르고,
 * 그 표는 {@code docs/backend/turn-control.md} 의 「기동할 때 남은 실행 정리」 가 갖는다.
 *
 * <p><b>같은 실행을 몇 번 적으려 해도 한 번만 적힌다.</b> 근거는 실행 줄 하나다. 트랜잭션에서 그 줄을 잠그고
 * {@code RUNNING} 이 아니면 아무것도 하지 않는다. 메시지와 session 은 그 트랜잭션 안에서만 적는다. 끝 사건과
 * 결과물 묶기와 알림은 트랜잭션이 끝난 뒤, 실제로 적었을 때만 한다.
 *
 * <p>끝 사건과 결과물 묶기와 알림은 커밋 뒤에 한 번만 한다. 커밋과 그 일들 사이에 프로세스가 죽으면 다시 하지
 * 않는다. 줄이 이미 끝난 상태라 다음 기동이 그 줄을 다시 적지 않기 때문이다. 위임 결과만은 전했다는 표시가 실행
 * 줄에 있어 기동 뒤 깨우기가 전한다.
 *
 * <p>Hermes 에 묻고 기다리는 것, turn 잠금, 대기 줄을 멈추는 것은 여기서 하지 않는다. 부르는 쪽이 한다. 다시
 * 붙어 끝난 대화 turn 은 Memory 제안과 추천 질문 갱신을 돌리지 않는다.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RecoveredRunRecorder {

    /** 물을 곳도 이어 갈 곳도 없는 실행에 적는 오류 코드다. */
    private static final String ORPHANED = "ORPHANED";

    private final TransactionTemplate transactions;
    private final AgentExecutionRepository executionRepository;
    private final ExecutionRecorder executions;
    private final ExecutionEventRecorder eventRecorder;
    private final ExecutionEventRepository executionEvents;
    private final AgentService agents;
    private final FlowRegistry flows;
    private final ConversationRepository conversations;
    private final ConversationWriter conversationWriter;
    private final ChatMessageRepository messages;
    private final DelegationOutput delegationOutput;
    private final ArtifactService artifacts;
    private final ConversationEventHub hub;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    /**
     * 끝난 결과를 적는다. 이미 끝난 줄이면 거짓이다.
     *
     * <p>실행 줄을 잠근 채 {@link ExecutionRecorder} 가 실제로 돈 모델을 알려고 Hermes 세션 조회를 한 번 부를 수
     * 있다. 한 실행에 한 번이고 {@code hermes.read-timeout} 안에 끝난다.
     *
     * <p>적다가 예외가 나면 그 트랜잭션에서 적은 것이 모두 되돌아가고 예외가 그대로 올라간다. 줄이
     * {@code RUNNING} 으로 남으므로 부르는 쪽이 다시 적을 수 있다.
     */
    public boolean settle(Long executionId, HermesRunResult result) {
        return finishOnce(
                executionId,
                row -> agents.findById(row.agentId())
                        .map(agent -> settleLocked(row, agent, result))
                        .orElseGet(() -> failLocked(row, ORPHANED)));
    }

    /** Hermes 의 결과 없이 FAILED 로 적는다. 이미 끝난 줄이면 거짓이다. 끝 사건의 detail 은 그 오류 코드다. */
    public boolean failWithout(Long executionId, String errorCode) {
        return finishOnce(executionId, row -> failLocked(row, errorCode));
    }

    /**
     * 남은 실행의 종류를 정한다.
     *
     * <p>위임 실행을 먼저 본다. 흐름 turn 아래에서 맡긴 위임도 위임 실행으로 적어야 부모 대화에 결과가 전해진다.
     * 루트 줄이나 그 에이전트를 읽지 못하면 흐름인지 알 수 없으므로 흐름 판정을 건너뛴다.
     */
    public RecoveredRunKind kindOf(AgentExecution row) {
        if (row.delegationKey() != null) {
            return RecoveredRunKind.DELEGATION;
        }
        if (rootHasFlow(row)) {
            return RecoveredRunKind.FLOW;
        }
        if (row.parentExecutionId() == null && row.conversationId() != null) {
            return RecoveredRunKind.CHAT_TURN;
        }
        return RecoveredRunKind.AUXILIARY;
    }

    private boolean rootHasFlow(AgentExecution row) {
        Long rootId = row.treeRootId();
        Optional<AgentExecution> root =
                Objects.equals(rootId, row.id()) ? Optional.of(row) : executionRepository.findById(rootId);
        return root.flatMap(it -> agents.findById(it.agentId()))
                .map(agent -> flows.find(agent.flow()) != null)
                .orElse(false);
    }

    /**
     * 실행 줄을 잠그고 {@code RUNNING} 일 때만 적은 뒤, 트랜잭션이 끝나고 나서 끝 사건과 알림을 낸다.
     *
     * @param write 잠근 줄을 받아 적고, 트랜잭션 뒤에 할 일을 돌려준다
     */
    private boolean finishOnce(Long executionId, Function<AgentExecution, Written> write) {
        Written written = transactions.execute(status -> executionRepository
                .lockById(executionId)
                .filter(row -> row.status() == ExecutionStatus.RUNNING)
                .map(write)
                .orElse(null));
        if (written == null) {
            return false;
        }
        appendEndEvent(written);
        // 답에 결과물을 묶은 뒤에 알린다. 화면은 끝 사건을 받고 이력을 다시 읽는다.
        if (written.messageId() != null) {
            // 실행 줄은 대화 폴더를 만든 뒤에 생긴다. 그 시작 시각 뒤에 바뀐 HTML 은 모두 이 turn 의 것이다.
            artifacts.recordTurn(
                    written.row().conversationId(),
                    written.messageId(),
                    written.row().startedAt());
        }
        announce(written);
        return true;
    }

    private Written settleLocked(AgentExecution row, Agent agent, HermesRunResult result) {
        RecoveredRunKind kind = kindOf(row);
        ModelChoice requested = ModelChoice.stored(row.provider(), row.model(), row.reasoningEffort());
        if (kind == RecoveredRunKind.CHAT_TURN) {
            return settleChatTurn(row, agent, result, requested);
        }
        if (kind == RecoveredRunKind.DELEGATION) {
            return settleDelegation(row, agent, result, requested);
        }
        if (result.succeeded()) {
            // 흐름의 단계 순서와 합치기는 내려간 프로세스의 메모리에만 있었다. 루트는 성공으로 끝났어도 답을
            // 합쳐 줄 곳이 없어 실패로 적고 사용량만 남긴다.
            if (isFlowRoot(row, kind)) {
                AgentExecution saved = executions.fail(row, agent, result, requested, ORPHANED);
                return new Written(
                        saved, kind, ExecutionEventType.RUN_FAILED, ORPHANED, null, flowRootNotice(saved, kind, false));
            }
            AgentExecution saved = executions.complete(row, agent, result, requested);
            return new Written(saved, kind, ExecutionEventType.RUN_COMPLETED, null, null, null);
        }
        if (isCancelled(result)) {
            AgentExecution saved = executions.cancel(row, agent, result, requested);
            return new Written(
                    saved, kind, ExecutionEventType.RUN_CANCELLED, null, null, flowRootNotice(saved, kind, true));
        }
        String code = errorCodeOf(result);
        AgentExecution saved = executions.fail(row, agent, result, requested, code);
        return new Written(saved, kind, ExecutionEventType.RUN_FAILED, code, null, flowRootNotice(saved, kind, false));
    }

    private static boolean isFlowRoot(AgentExecution row, RecoveredRunKind kind) {
        return kind == RecoveredRunKind.FLOW && row.parentExecutionId() == null;
    }

    /**
     * 흐름 turn 의 루트 줄이 끝났을 때 그 대화의 화면에 낼 사건이다. 루트 줄이 아니거나 대화가 없으면 null 이다.
     *
     * <p>화면은 이 사건을 받고 「답을 만드는 중」 을 내린다. 흐름은 이어 가지 못하므로 답 없이 끝난다.
     */
    private ChatEvent flowRootNotice(AgentExecution row, RecoveredRunKind kind, boolean cancelled) {
        if (!isFlowRoot(row, kind)) {
            return null;
        }
        Conversation conversation = liveConversation(row);
        if (conversation == null) {
            return null;
        }
        return cancelled ? ChatEvent.stopped(conversation.publicId(), null, row.id()) : failureNotice(false);
    }

    private Written settleChatTurn(AgentExecution row, Agent agent, HermesRunResult result, ModelChoice requested) {
        Conversation conversation = liveConversation(row);
        if (result.succeeded()) {
            AgentExecution saved = executions.complete(row, agent, result, requested);
            if (conversation == null) {
                return new Written(
                        saved, RecoveredRunKind.CHAT_TURN, ExecutionEventType.RUN_COMPLETED, null, null, null);
            }
            Long messageId = saveAnswer(saved, result.output() == null ? "" : result.output());
            conversationWriter.touchSession(conversation.id(), blankToNull(result.sessionId()), clock.instant());
            return new Written(
                    saved,
                    RecoveredRunKind.CHAT_TURN,
                    ExecutionEventType.RUN_COMPLETED,
                    null,
                    messageId,
                    ChatEvent.done(conversation.publicId(), messageId, saved.id()));
        }
        if (isCancelled(result)) {
            AgentExecution saved = executions.cancel(row, agent, result, requested);
            if (conversation == null) {
                return new Written(
                        saved, RecoveredRunKind.CHAT_TURN, ExecutionEventType.RUN_CANCELLED, null, null, null);
            }
            String answer = result.output();
            Long messageId = answer == null || answer.isBlank() ? null : saveAnswer(saved, answer);
            String sessionId = blankToNull(result.sessionId());
            if (sessionId != null) {
                conversationWriter.touchSession(conversation.id(), sessionId, clock.instant());
            }
            return new Written(
                    saved,
                    RecoveredRunKind.CHAT_TURN,
                    ExecutionEventType.RUN_CANCELLED,
                    null,
                    messageId,
                    ChatEvent.stopped(conversation.publicId(), messageId, saved.id()));
        }
        String code = errorCodeOf(result);
        AgentExecution saved = executions.fail(row, agent, result, requested, code);
        ChatEvent notice = conversation == null ? null : failureNotice(result.providerBlocked());
        return new Written(saved, RecoveredRunKind.CHAT_TURN, ExecutionEventType.RUN_FAILED, code, null, notice);
    }

    private Written settleDelegation(AgentExecution row, Agent agent, HermesRunResult result, ModelChoice requested) {
        if (result.succeeded()) {
            AgentExecution saved =
                    executions.complete(row, agent, result, requested, delegationOutput.clip(result.output()));
            return new Written(saved, RecoveredRunKind.DELEGATION, ExecutionEventType.RUN_COMPLETED, null, null, null);
        }
        if (isCancelled(result)) {
            AgentExecution saved =
                    executions.cancel(row, agent, result, requested, delegationOutput.partial(result.output()));
            return new Written(saved, RecoveredRunKind.DELEGATION, ExecutionEventType.RUN_CANCELLED, null, null, null);
        }
        String code = errorCodeOf(result);
        AgentExecution saved = executions.fail(row, agent, result, requested, code);
        return new Written(saved, RecoveredRunKind.DELEGATION, ExecutionEventType.RUN_FAILED, code, null, null);
    }

    /** Hermes 의 결과 없이 실패로 적는다. 사용량을 적지 않는다. */
    private Written failLocked(AgentExecution row, String errorCode) {
        RecoveredRunKind kind = kindOf(row);
        boolean turnRoot = kind == RecoveredRunKind.CHAT_TURN || isFlowRoot(row, kind);
        ChatEvent notice = turnRoot && liveConversation(row) != null ? failureNotice(false) : null;
        return new Written(
                executions.fail(row, errorCode), kind, ExecutionEventType.RUN_FAILED, errorCode, null, notice);
    }

    /** 그 실행의 대화다. 대화가 없거나 지워졌으면 null 이고, 그때는 실행 줄만 적는다. */
    private Conversation liveConversation(AgentExecution row) {
        if (row.conversationId() == null) {
            return null;
        }
        return conversations
                .findById(row.conversationId())
                .filter(conversation -> conversation.deletedAt() == null)
                .orElse(null);
    }

    /**
     * 답을 대화에 남기고 그 메시지 번호를 돌려준다. 그 실행의 메시지가 이미 있으면 남기지 않고 null 이다.
     *
     * <p>대화의 마지막 유효 메시지가 답이면 그 답을 다시 생성하던 turn 이다. 다시 생성은 질문을 새로 저장하지
     * 않기 때문이다. 그때는 앞 답을 대신한 것으로 적는다.
     *
     * <p>다시 생성인지는 저장된 값이 아니라 마지막 유효 메시지로 미루어 정한다. 그래서 한 대화에 {@code RUNNING}
     * 루트 줄이 둘이면 뒤에 적는 답이 앞에 적은 답을 대신한 것으로 저장된다. 대화 하나에는 도는 turn 이 하나뿐이라
     * 보통 생기지 않는다.
     */
    private Long saveAnswer(AgentExecution row, String answer) {
        if (messages.existsByExecutionId(row.id())) {
            return null;
        }
        Long conversationId = row.conversationId();
        List<ChatMessage> active = activeMessages(conversationId);
        ChatMessage last = active.isEmpty() ? null : active.getLast();
        Instant now = clock.instant();
        ChatMessage message = last != null && last.role() == MessageRole.ASSISTANT
                ? ChatMessage.regeneratedAnswer(conversationId, answer, row.id(), last.id(), now)
                : ChatMessage.fromAssistant(conversationId, answer, row.id(), now);
        return messages.save(message).id();
    }

    /** 다른 메시지가 대신하지 않은 메시지들이다. {@code ChatService} 가 다시 생성할 답을 고르는 기준과 같다. */
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
     * 끝 사건을 그 실행의 마지막 순번 다음에 남긴다.
     *
     * <p>트랜잭션 밖에서 따로 저장한다. 사건은 관측용이라 저장이 실패해도 실행 줄과 메시지는 남아야 한다.
     * {@code ChatService} 와 {@code AgentRunner} 가 같은 판단을 한다.
     */
    private void appendEndEvent(Written written) {
        if (written.endEvent() == null) {
            return;
        }
        AgentExecution row = written.row();
        try {
            int sequence = executionEvents.lastSequence(row.id()) + 1;
            executionEvents.save(eventRecorder.record(row, written.endEvent(), written.detail(), sequence));
        } catch (RuntimeException ex) {
            log.warn("다시 정한 실행의 끝 사건을 남기지 못했다 executionId={}", row.id(), ex);
        }
    }

    /** 화면과 부모 대화에 끝났음을 알린다. 알리지 못해도 실행 줄은 이미 적혔으므로 로그만 남긴다. */
    private void announce(Written written) {
        AgentExecution row = written.row();
        try {
            if (written.notice() != null) {
                hub.publish(row.conversationId(), written.notice());
            }
            if (written.kind() == RecoveredRunKind.DELEGATION && row.conversationId() != null) {
                events.publishEvent(new DelegationFinished(row.conversationId(), row.id()));
            }
        } catch (RuntimeException ex) {
            log.warn("다시 정한 실행이 끝났음을 알리지 못했다 executionId={}", row.id(), ex);
        }
    }

    /** 보통 turn 이 실패했을 때 화면에 내는 오류와 같은 코드와 글이다. */
    private static ChatEvent failureNotice(boolean providerBlocked) {
        return providerBlocked
                ? ChatEvent.error(ErrorCode.PROVIDER_BLOCKED.name(), "every account of the chosen provider is blocked")
                : ChatEvent.error(ErrorCode.HERMES_RUN_FAILED.name(), "the agent run did not complete");
    }

    private static boolean isCancelled(HermesRunResult result) {
        return "cancelled".equalsIgnoreCase(result.status());
    }

    /** 보통 turn 이 실패한 실행 줄에 적는 것과 같은 오류 코드다. */
    private static String errorCodeOf(HermesRunResult result) {
        if (result.providerBlocked()) {
            return ErrorCode.PROVIDER_BLOCKED.name();
        }
        return result.status() == null ? "UNKNOWN" : result.status().toUpperCase();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * 트랜잭션에서 적은 것과 그 뒤에 할 일이다.
     *
     * @param row 적은 실행 줄
     * @param endEvent 남길 끝 사건
     * @param detail 끝 사건의 detail. 실패면 오류 코드다
     * @param messageId 저장한 답 메시지. 저장하지 않았으면 null
     * @param notice 대화 단위 SSE 로 낼 사건. 내지 않으면 null
     */
    private record Written(
            AgentExecution row,
            RecoveredRunKind kind,
            ExecutionEventType endEvent,
            String detail,
            Long messageId,
            ChatEvent notice) {}
}
