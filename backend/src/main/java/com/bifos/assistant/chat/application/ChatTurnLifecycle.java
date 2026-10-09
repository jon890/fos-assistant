package com.bifos.assistant.chat.application;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.chat.application.model.CheckAnswer;
import com.bifos.assistant.chat.domain.ChatMessage;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.domain.ExecutionQuestion;
import com.bifos.assistant.chat.domain.RunSession;
import com.bifos.assistant.chat.infra.ChatMessageRepository;
import com.bifos.assistant.chat.infra.ExecutionQuestionRepository;
import com.bifos.assistant.context.AssembledContext;
import com.bifos.assistant.hermes.dto.HermesImage;
import com.bifos.assistant.hermes.dto.HermesRunCommand;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.memory.application.MemoryProposer;
import com.bifos.assistant.model.domain.ModelChoice;
import com.bifos.assistant.model.domain.type.ModelTier;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.application.ExecutionContextSnapshot;
import com.bifos.assistant.usage.application.ExecutionRecorder;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.type.ExecutionEventType;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** turn 의 명령과 실행 줄을 만들고 답과 종료 상태를 남긴다. */
@Component
@Slf4j
@RequiredArgsConstructor
class ChatTurnLifecycle {
    private final ConversationWriter conversationWriter;
    private final ConversationSessions sessions;
    private final ChatMessageRepository messages;
    private final ExecutionQuestionRepository executionQuestions;
    private final ExecutionRecorder executions;
    private final MemoryProposer memoryProposer;
    private final StarterSuggestionService starterSuggestions;
    private final ArtifactService artifacts;
    private final Clock clock;
    private final ResultDeliveryRecorder resultDeliveries;
    private final ChatRunEvents chatRunEvents;

    /**
     * 대화가 고른 값으로 명령과 실행 줄을 만든다. 사용자 메시지는 이미 저장돼 있다.
     *
     * <p>고르지 않은 provider, 모델, effort 는 null 그대로 실어 profile 의 기본값으로 돌게 둔다.
     *
     * <p>보낼 session 을 명령보다 먼저 정한다. 실행 줄에는 그 대화의 루트 session 을 적는다. 압축 교체 뒤에는
     * 보내는 session 과 적는 session 이 다르다(ADR-031).
     */
    PendingTurn begin(
            CurrentUser user,
            Routed routed,
            String text,
            List<HermesImage> images,
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
                choice.reasoningEffort(),
                images);
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
    void startCheck(CheckTurn check, PendingTurn pending) {
        try {
            check.started(pending.execution().id(), pending.conversation().hermesRootSessionId());
        } catch (RuntimeException ex) {
            String code = ex instanceof ApiException api ? api.code().name() : ErrorCode.INTERNAL_ERROR.name();
            executions.fail(pending.execution(), code);
            chatRunEvents.append(pending, ExecutionEventType.RUN_FAILED, code);
            throw ex;
        }
    }

    /**
     * 자동 turn 의 전달 시도에 그 turn 의 실행 줄을 잇는다(ADR-075).
     *
     * <p>실패해도 던지지 않는다. 던지면 방금 만든 실행 줄이 {@code RUNNING} 으로 남는다. 이 뒤에는 그 줄을 실패로 적는
     * 경로가 없다. 시도는 이어지지 않은 채 turn 이 끝난 방식으로 닫힌다.
     */
    void attachDeliveryExecution(Long attemptId, Long executionId) {
        try {
            resultDeliveries.attachExecution(attemptId, executionId);
        } catch (RuntimeException ex) {
            log.warn("전달 시도에 실행 줄을 잇지 못했다 attemptId={} executionId={}", attemptId, executionId, ex);
        }
    }

    ChatTurn finish(PendingTurn pending, HermesRunResult result, ModelChoice requested) {
        Instant now = clock.instant();
        pending.conversation().rememberSession(result.sessionId(), now);
        conversationWriter.touchSession(
                pending.conversation().id(),
                result.sessionId() == null || result.sessionId().isBlank() ? null : result.sessionId(),
                now);

        AgentExecution execution = executions.complete(pending.execution(), pending.agent(), result, requested);
        chatRunEvents.append(pending, ExecutionEventType.RUN_COMPLETED, null);
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
    ChatTurn finishCheck(PendingTurn pending, CheckTurn check, Long executionId, String output) {
        CheckAnswer checked = check.answer(executionId, output);
        Conversation conversation = pending.conversation();
        if (checked.omit() || check.silent()) {
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

    ChatTurn cancel(PendingTurn pending, HermesRunResult result, ModelChoice requested) {
        AgentExecution execution = executions.cancel(pending.execution(), pending.agent(), result, requested);
        String answer;
        synchronized (pending) {
            chatRunEvents.append(pending, ExecutionEventType.RUN_CANCELLED, null);
            answer = result != null
                            && result.output() != null
                            && !result.output().isBlank()
                    ? result.output()
                    : pending.streamed().toString();
        }
        if (pending.intent() instanceof TurnIntent.ProactiveCheck proactive) {
            // 검사하지 않은 답이 대화에 남지 않게 그때까지의 답 대신 멈춤 알림 줄만 남긴다.
            answer = "";
            String stopped = proactive.check().stoppedNotice();
            if (!proactive.check().silent()) {
                ChatMessage notice = messages.save(
                        ChatMessage.fromSystem(pending.conversation().id(), stopped, clock.instant()));
                pending.onEvent()
                        .accept(ChatEvent.system(pending.conversation().publicId(), notice.id(), notice.content()));
            }
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

    /**
     * 사람이 보낸 turn 의 실행에 질문을 잇는다(ADR-20261007 / memory-remember). 잇지 못해도 turn 은 잇는다. 그 실행의 {@code memory_remember} 는 제안으로만
     * 남는다.
     */
    void recordQuestion(Long executionId, Long questionId) {
        if (questionId == null) {
            return;
        }
        try {
            executionQuestions.save(ExecutionQuestion.of(executionId, questionId, clock.instant()));
        } catch (RuntimeException ex) {
            log.warn("could not link the turn question executionId={}", executionId, ex);
        }
    }

    ChatMessage answerMessage(PendingTurn pending, String answer, Long executionId) {
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

    /**
     * 이 turn 이 대화 폴더에 만든 HTML 을 답에 묶고 turn 을 그대로 돌려준다.
     *
     * <p>turn 을 돌려주는 자리마다 부른다. 끝 사건을 보내는 자리에 두면 스트림이 아닌 경로가 빠진다. 묶기가
     * 실패해도 turn 은 성공으로 끝난다. 근거는 ADR-027 에 있다.
     */
    ChatTurn recorded(ChatTurn turn, Instant startedAt) {
        artifacts.recordTurn(turn.conversationId(), turn.messageId(), startedAt);
        return turn;
    }
}
