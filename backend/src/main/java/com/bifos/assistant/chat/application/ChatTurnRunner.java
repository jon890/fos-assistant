package com.bifos.assistant.chat.application;

import com.bifos.assistant.chat.domain.ChatAttachment;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ArtifactStore;
import com.bifos.assistant.context.AssembledContext;
import com.bifos.assistant.context.ContextAssembler;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.model.domain.ModelChoice;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.skill.application.SkillUseRecorder;
import com.bifos.assistant.usage.application.ContextSourceRef;
import com.bifos.assistant.usage.application.ExecutionContextSnapshot;
import com.bifos.assistant.usage.application.ExecutionRecorder;
import com.bifos.assistant.usage.domain.type.ExecutionEventType;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 대화가 고른 경로에서 turn 을 실행한다. */
@Component
@RequiredArgsConstructor
class ChatTurnRunner {
    private final ExecutionRecorder executions;
    private final ContextAssembler contextAssembler;
    private final AttachmentService attachments;
    private final ArtifactStore artifactStore;
    private final ArtifactService artifacts;
    private final TurnCancellation turns;
    private final SkillUseRecorder skillUses;
    private final ModelTierService modelTiers;
    private final Clock clock;
    private final ChatTurnRouting chatTurnRouting;
    private final ChatTurnLifecycle chatTurnLifecycle;
    private final ChatRunEvents chatRunEvents;
    private final ChatTurnStopper chatTurnStopper;

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
    ChatTurn runTurn(
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
        TurnHandle handle = existingHandle == null ? chatTurnRouting.openTurn(user, routed) : existingHandle;
        boolean closesHandle = existingHandle == null;
        try {
            Long questionId = chatTurnRouting.saveQuestion(user, conversation, text, attachmentIds, intent, onEvent);
            // 폴더를 만들기 전에 잡는다. 이 시각 뒤에 바뀐 HTML 이 이 turn 의 결과물이다.
            Instant startedAt = clock.instant();
            artifactStore.ensureFolder(conversation.id());
            SkillCommand command = routed.command();
            String asked = command == null ? text : command.hermesInput();
            AgentInput agentInput = attachments.agentInput(conversation.id(), routed.attached(), asked, true);
            String input = artifacts.agentPreamble(conversation) + agentInput.text();
            // 커넥터 에이전트의 실행에는 Memory 문맥을 주지 않는다(ADR-045). turn 지시는 그대로 붙는다.
            AssembledContext context = routed.agent().connectorManaged()
                    ? AssembledContext.empty()
                    : contextAssembler.assemble(user, routed.agent().id());
            // 먼저 살펴보기는 memory_remember 를 받지 않으므로 기억 지침도 싣지 않는다(ADR-080, ADR-20261007 / memory-remember)
            context = contextAssembler.withResponseInstructions(
                    context, !routed.agent().connectorManaged() && !(intent instanceof TurnIntent.ProactiveCheck));
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
                PendingTurn failed = chatTurnLifecycle.begin(
                        user,
                        routed,
                        input,
                        agentInput.images(),
                        context,
                        snapshot,
                        ModelChoice.defaults(),
                        null,
                        intent,
                        requestReceivedAt,
                        onEvent);
                String code = ex instanceof ApiException api ? api.code().name() : "MODEL_TIER_RESOLVE_FAILED";
                executions.fail(failed.execution(), code);
                chatRunEvents.append(failed, ExecutionEventType.RUN_FAILED, code);
                throw ex;
            }
            ModelChoice choice = resolved.choice();
            PendingTurn pending = chatTurnLifecycle.begin(
                    user,
                    routed,
                    input,
                    agentInput.images(),
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
            chatTurnLifecycle.recordQuestion(pending.execution().id(), questionId);
            turns.rekey(handle, pending.execution().id());
            if (intent instanceof TurnIntent.ProactiveCheck proactive) {
                chatTurnLifecycle.startCheck(proactive.check(), pending);
            }
            if (streaming) {
                onEvent.accept(ChatEvent.started(
                        conversation.publicId(), pending.execution().id()));
            }

            if (turns.isStopConfirmed(handle)
                    || turns.shouldStopBeforeSubmit(pending.execution().id())) {
                return chatTurnStopper.stoppedTurn(handle, pending, null, choice, startedAt);
            }
            String runId = null;
            HermesRunResult result;
            try {
                runId = chatRunEvents.submit(pending);
                // 한 번에 받는 경로도 사건 스트림을 연다. 화면으로 흘릴 곳은 없고 도구 사건을 실행 기록에 남기려는 것이다(ADR-090).
                chatRunEvents.relay(pending, runId, handle, streaming ? onEvent : null);
                result = chatRunEvents.awaitCompletion(pending, runId);
            } catch (RuntimeException ex) {
                chatTurnStopper.cancelAndHoldIfStopConfirmed(handle, pending, choice);
                throw ex;
            } finally {
                turns.untrackRun(pending.execution().id(), runId);
            }

            if (turns.isStopConfirmed(handle) || "cancelled".equalsIgnoreCase(result.status())) {
                return chatTurnStopper.stoppedTurn(handle, pending, result, choice, startedAt);
            }
            if (result.succeeded()) {
                ChatTurn completed = chatTurnLifecycle.finish(pending, result, choice);
                turns.markFinished(handle);
                return chatTurnLifecycle.recorded(completed, startedAt);
            }
            if (result.providerBlocked()) {
                executions.fail(
                        pending.execution(), pending.agent(), result, choice, ErrorCode.PROVIDER_BLOCKED.name());
                chatRunEvents.append(pending, ExecutionEventType.RUN_FAILED, ErrorCode.PROVIDER_BLOCKED.name());
                throw new ApiException(ErrorCode.PROVIDER_BLOCKED, "every account of the chosen provider is blocked");
            }
            executions.fail(pending.execution(), pending.agent(), result, choice, hermesStatus(result));
            chatRunEvents.append(pending, ExecutionEventType.RUN_FAILED, hermesStatus(result));
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
    ChatTurn runFlow(
            CurrentUser user,
            Routed routed,
            String text,
            TurnIntent intent,
            Consumer<ChatEvent> onEvent,
            boolean streaming,
            TurnHandle existingHandle) {
        Conversation conversation = routed.conversation();
        TurnHandle handle = existingHandle == null ? chatTurnRouting.openTurn(user, routed) : existingHandle;
        boolean closesHandle = existingHandle == null;
        try {
            if (intent instanceof TurnIntent.Fresh) {
                chatTurnRouting.fillBlankTitle(conversation, text);
            }
            Instant startedAt = clock.instant();
            artifactStore.ensureFolder(conversation.id());
            String input = artifacts.agentPreamble(conversation)
                    + attachments
                            .agentInput(conversation.id(), routed.attached(), text, false)
                            .text();
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
                chatTurnStopper.cancelFlowAndHoldIfStopConfirmed(handle, conversation.id(), rootExecutionId.get());
                throw ex;
            }
            if (!turn.cancelled()) {
                turns.markFinished(handle);
                chatTurnLifecycle.recorded(turn, startedAt);
            } else {
                chatTurnStopper.recordStoppedFlow(handle, turn, startedAt);
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
     * 실행에 남길 문맥 항목의 참조다. 결과를 전하는 turn 이면 Memory 항목 뒤로 입력에 실은 결과 항목을 잇는다(ADR-071).
     *
     * <p>커넥터 에이전트의 turn 은 Memory 항목이 없어 결과 항목만 남는다.
     */
    static List<ContextSourceRef> sourceRefs(AssembledContext context, TurnIntent intent) {
        if (!(intent instanceof TurnIntent.DelegationResults results)
                || results.items().isEmpty()) {
            return ContextSourceRefs.of(context);
        }
        List<ContextSourceRef> refs = new ArrayList<>(ContextSourceRefs.of(context));
        refs.addAll(ContextSourceRefs.of(results.items()));
        return refs;
    }

    static String hermesStatus(HermesRunResult result) {
        return result.status() == null ? "UNKNOWN" : result.status().toUpperCase();
    }
}
