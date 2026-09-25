package com.bifos.assistant.orchestration.application;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.chat.application.ChatEvent;
import com.bifos.assistant.chat.application.ChatTurn;
import com.bifos.assistant.chat.application.TurnIntent;
import com.bifos.assistant.chat.application.TurnCancellation;
import com.bifos.assistant.chat.domain.ChatMessage;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ChatMessageRepository;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.orchestration.domain.ChildResult;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.application.ExecutionRecorder;
import com.bifos.assistant.usage.application.ExecutionEventRecorder;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.ExecutionEventType;
import com.bifos.assistant.usage.infra.ExecutionEventRepository;
import java.util.ArrayList;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 조사와 구현을 나란히 돌리고 그 둘을 합쳐 답한다.
 *
 * <p><b>참조 구현이다. 넓히지 않는다.</b> 이 흐름 하나만 있고 범용 엔진이 아니다. Task 를 자유롭게
 * 정의하는 기능도, 일반 의존 그래프도, 재시도도 없다. 기반이 서는 것을 보여 준 실험이고 그 역할은
 * 끝났다.
 *
 * <p><b>새 흐름을 Java 로 더하지 않는다.</b> {@code CareerFlow} 나 {@code TravelFlow} 를 여기 더하기
 * 시작하면 이 저장소가 workflow engine 이 된다. 앞으로 에이전트 조합은 Hermes 가 정하고 Control
 * Plane 은 경계만 갖는다. 근거는 ADR-017 에 있다.
 *
 * <p>단계가 넷이다.
 *
 * <pre>
 * 사용자 요청 → Chief → Researcher 와 Engineer 를 나란히 → Synthesizer → 최종 답
 * </pre>
 *
 * <p>Chief 가 뿌리이고 나머지 셋은 Chief 의 자식이다. 에이전트를 새로 만들지 않고 같은 에이전트를
 * 다른 지시로 부른다. 어느 에이전트가 어느 단계를 맡을지는 Control Plane 이 정하고, 모델이 정하지
 * 못한다.
 *
 * <p>실패하면 거기서 멈춘다. 재시도하지 않는다. 몇 번 돌았는지가 비용과 얽히고, 그것을 이 단계에서
 * 풀지 않는다.
 */
@Service
@RequiredArgsConstructor
public class ResearchAndBuildFlow implements Flow {

    private static final Logger log = LoggerFactory.getLogger(ResearchAndBuildFlow.class);

    /** {@code agent.flow} 에 적는 이름이다. */
    public static final String NAME = "research-and-build";

    private static final String CHIEF = "chief";
    private static final String RESEARCHER = "researcher";
    private static final String ENGINEER = "engineer";
    private static final String SYNTHESIZER = "synthesizer";

    private static final String STARTED = "started";
    private static final String COMPLETED = "completed";
    private static final String FAILED = "failed";

    private final ChatMessageRepository messages;
    private final ConversationRepository conversations;
    private final AgentRunner runner;
    private final ChildExecutionRunner children;
    private final ExecutionRecorder executions;
    private final ExecutionEventRecorder eventRecorder;
    private final ExecutionEventRepository executionEvents;
    private final TurnCancellation cancellation;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ChatTurn run(
            CurrentUser user,
            Conversation conversation,
            Agent agent,
            String text,
            String input,
            TurnIntent intent,
            Consumer<AgentExecution> onRootStarted,
            Consumer<ChatEvent> onEvent) {
        if (intent instanceof TurnIntent.Fresh) {
            messages.save(ChatMessage.fromUser(conversation.id(), user.id(), text));
        } else if (intent instanceof TurnIntent.Edit edit) {
            messages.save(ChatMessage.editedFromUser(
                    conversation.id(), user.id(), text, edit.previousQuestion().id()));
        }

        onEvent.accept(ChatEvent.step(CHIEF, STARTED));
        AtomicReference<Long> rootExecutionId = new AtomicReference<>();
        AgentRunner.Run chief = runner.run(
                user, conversation, agent, chiefPrompt(input), null, null, conversation.hermesSessionId(),
                execution -> {
                    rootExecutionId.set(execution.id());
                    onRootStarted.accept(execution);
                },
                (execution, runId) -> cancellation.trackRun(
                        execution.id(), agent.apiBaseUrl(), agent.hermesProfile(), runId),
                () -> {
                    Long executionId = rootExecutionId.get();
                    return executionId != null && shouldStop(executionId);
                }, TurnIntent.instructionFor(intent));
        AgentExecution root = chief.execution();
        cancellation.untrackRun(root.id(), root.hermesRunId());
        if (shouldStop(root.id())) {
            return cancelled(conversation, root, chief.sessionId());
        }
        if (!chief.result().succeeded()) {
            onEvent.accept(ChatEvent.step(CHIEF, FAILED));
            throw new ApiException(ErrorCode.HERMES_RUN_FAILED, "the flow could not start");
        }
        conversation.rememberSession(chief.sessionId());
        conversations.touchSession(conversation.id(),
                chief.sessionId() == null || chief.sessionId().isBlank() ? null : chief.sessionId(), Instant.now());
        onEvent.accept(ChatEvent.step(CHIEF, COMPLETED));

        Split split = split(root, chief.result().output(), onEvent);

        if (shouldStop(root.id())) {
            return cancelled(conversation, root, null);
        }

        if (split.isEmpty()) {
            // 나눌 것이 없으면 Chief 의 답이 그대로 최종 답이다. 실행은 하나만 남는다.
            return answer(conversation, root, chief.result().output(), intent);
        }

        List<Step> planned = plan(split);
        planned.forEach(step -> onEvent.accept(ChatEvent.step(step.name(), STARTED)));
        List<ChildResult> done = runInParallel(user, conversation, root, agent, planned, intent);
        if (shouldStop(root.id())) {
            return cancelled(conversation, root, null);
        }
        for (int index = 0; index < planned.size(); index++) {
            ChildResult result = done.get(index);
            onEvent.accept(
                    ChatEvent.step(planned.get(index).name(), result.succeeded() ? COMPLETED : FAILED));
        }
        ChildResult firstFailure =
                done.stream().filter(result -> !result.succeeded()).findFirst().orElse(null);
        if (firstFailure != null) {
            if (shouldStop(root.id())) {
                return cancelled(conversation, root, null);
            }
            throw stop(root, firstFailure.errorCode());
        }

        if (shouldStop(root.id())) {
            return cancelled(conversation, root, null);
        }

        onEvent.accept(ChatEvent.step(SYNTHESIZER, STARTED));
        ChildResult synthesis = runChild(
                user, conversation, root, agent, synthesizerPrompt(text, done), intent);
        if (shouldStop(root.id())) {
            return cancelled(conversation, root, null);
        }
        if (!synthesis.succeeded()) {
            onEvent.accept(ChatEvent.step(SYNTHESIZER, FAILED));
            throw stop(root, synthesis.errorCode());
        }
        onEvent.accept(ChatEvent.step(SYNTHESIZER, COMPLETED));

        return answer(conversation, root, synthesis.output(), intent);
    }

    /** 나눈 결과에서 실제로 돌릴 단계를 고른다. 갈래가 빈 단계는 건너뛴다. */
    private static List<Step> plan(Split split) {
        List<Step> planned = new ArrayList<>();
        if (!split.research().isBlank()) {
            planned.add(new Step(RESEARCHER, researcherPrompt(split.research())));
        }
        if (!split.build().isBlank()) {
            planned.add(new Step(ENGINEER, engineerPrompt(split.build())));
        }
        return List.copyOf(planned);
    }

    /**
     * Researcher 와 Engineer 를 함께 띄운다.
     *
     * <p><b>한쪽이 실패해도 다른 쪽을 기다린다.</b> 돌고 있는 것을 중간에 끊지 않는다. 끊어도 Hermes
     * 쪽 실행은 계속 돌고 토큰은 이미 쓰인다. 둘 다 끝난 뒤에 판정한다.
     *
     * <p>단계 사건은 여기서 내지 않는다. 스트림은 하나이고 두 스레드가 함께 쓰면 순서가 흔들린다.
     * 부르는 쪽이 띄우기 전과 거둔 뒤에 한 스레드에서 낸다.
     */
    private List<ChildResult> runInParallel(
            CurrentUser user,
            Conversation conversation,
            AgentExecution root,
            Agent agent,
            List<Step> planned,
            TurnIntent intent) {
        try (ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<ChildResult>> pending = planned.stream()
                    .map(step -> CompletableFuture.supplyAsync(
                            () -> {
                                if (shouldStop(root.id())) {
                                    return ChildResult.failed(null, "CANCELLED");
                                }
                                return runChild(user, conversation, root, agent, step.task(), intent);
                            },
                            workers))
                    .toList();
            return pending.stream().map(CompletableFuture::join).toList();
        }
    }

    private ChildResult runChild(CurrentUser user, Conversation conversation,
            AgentExecution root, Agent agent, String task, TurnIntent intent) {
        AtomicReference<String> submittedRunId = new AtomicReference<>();
        try {
            return children.run(user, conversation, root, agent.code(), task,
                    (execution, runId) -> {
                        submittedRunId.set(runId);
                        cancellation.trackRun(root.id(), agent.apiBaseUrl(), agent.hermesProfile(), runId);
                    },
                    () -> shouldStop(root.id()),
                    TurnIntent.instructionFor(intent));
        } finally {
            cancellation.untrackRun(root.id(), submittedRunId.get());
        }
    }

    private boolean shouldStop(Long executionId) {
        return cancellation.isStopConfirmed(executionId) || cancellation.shouldStopBeforeSubmit(executionId);
    }

    /**
     * Chief 의 답을 나눈다.
     *
     * <p>파싱하지 못하면 그 흐름을 실패시킨다. 파싱 실패를 무시하고 원문을 그대로 넘기면 무엇이
     * 잘못됐는지 모른 채 세 실행이 더 돈다.
     */
    private Split split(AgentExecution root, String output, Consumer<ChatEvent> onEvent) {
        try {
            JsonNode json = objectMapper.readTree(unwrap(output));
            return new Split(
                    json.path("research").asString("").strip(), json.path("build").asString("").strip());
        } catch (RuntimeException ex) {
            onEvent.accept(ChatEvent.step(CHIEF, FAILED));
            executions.fail(root, ErrorCode.ORCHESTRATION_CONTRACT_BROKEN.name());
            throw new ApiException(
                    ErrorCode.ORCHESTRATION_CONTRACT_BROKEN, "the first step did not answer with JSON");
        }
    }

    /** 흐름을 멈춘다. 어느 단계가 실패했는지를 Chief 의 실행 줄에 적는다. */
    private ApiException stop(AgentExecution root, String errorCode) {
        executions.fail(root, errorCode == null ? ErrorCode.ORCHESTRATION_STEP_FAILED.name() : errorCode);
        return new ApiException(ErrorCode.ORCHESTRATION_STEP_FAILED, "a step of the flow failed");
    }

    /**
     * 최종 답을 대화 이력에 남긴다.
     *
     * <p>이력에 붙이는 실행 번호는 뿌리다. 그 번호로 실행 나무를 열면 네 단계를 모두 볼 수 있다.
     * 마지막 단계를 붙이면 잎 하나만 보인다.
     */
    private ChatTurn answer(
            Conversation conversation, AgentExecution root, String output, TurnIntent intent) {
        String text = output == null ? "" : output;
        ChatMessage saved = messages.save(intent instanceof TurnIntent.Regenerate regenerate
                        && regenerate.previousAnswer() != null
                ? ChatMessage.regeneratedAnswer(
                        conversation.id(), text, root.id(), regenerate.previousAnswer().id())
                : ChatMessage.fromAssistant(conversation.id(), text, root.id()));
        return new ChatTurn(conversation.id(), root.id(), text, saved.id(), false);
    }

    /** 중지한 turn 은 답과 기억을 더 만들지 않고 Chief 를 취소 상태로 남긴다. */
    private ChatTurn cancelled(Conversation conversation, AgentExecution root, String sessionId) {
        executions.cancel(root);
        try {
            boolean alreadyRecorded = executionEvents
                    .findByExecutionIdInOrderByExecutionIdAscSequenceAsc(List.of(root.id()))
                    .stream().anyMatch(event -> event.eventType() == ExecutionEventType.RUN_CANCELLED);
            if (!alreadyRecorded) {
                var event = eventRecorder.record(root, ExecutionEventType.RUN_CANCELLED, null, 99);
                if (event != null) executionEvents.save(event);
            }
        } catch (RuntimeException ex) {
            // 사건 기록 실패가 중지를 실패로 바꾸면 안 된다.
            log.warn("취소 실행 사건을 남기지 못했다 executionId={}", root.id(), ex);
        }
        if (sessionId != null && !sessionId.isBlank()) {
            conversation.rememberSession(sessionId);
            conversations.touchSession(conversation.id(), sessionId, Instant.now());
        }
        return new ChatTurn(conversation.id(), root.id(), "", null, true);
    }

    /**
     * 모델이 JSON 을 코드 블록으로 감싸 답하는 것을 벗긴다.
     *
     * <p>본문이 JSON 이 아닌 것은 그대로 파싱에서 걸린다. 여기서 보는 것은 감싼 표기뿐이다.
     */
    private static String unwrap(String output) {
        String trimmed = output == null ? "" : output.strip();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        int firstLineEnd = trimmed.indexOf('\n');
        int closing = trimmed.lastIndexOf("```");
        if (firstLineEnd < 0 || closing <= firstLineEnd) {
            return trimmed;
        }
        return trimmed.substring(firstLineEnd + 1, closing).strip();
    }

    private static String chiefPrompt(String text) {
        return """
                아래 요청을 읽고 조사할 것과 만들 것을 나눈다.
                JSON 하나만 답한다. 다른 글을 덧붙이지 않는다.
                모양은 {"research":"조사할 것 한 문단","build":"만들 것 한 문단"} 이다.
                조사할 것이 없으면 research 를, 만들 것이 없으면 build 를 빈 문자열로 둔다.

                요청:
                %s
                """.formatted(text);
    }

    private static String researcherPrompt(String research) {
        return """
                아래를 조사해 사실만 정리한다. 결론을 내지 않는다.

                %s
                """.formatted(research);
    }

    private static String engineerPrompt(String build) {
        return """
                아래를 만든다. 만든 것과 그 이유를 적는다.

                %s
                """.formatted(build);
    }

    private static String synthesizerPrompt(String text, List<ChildResult> done) {
        StringBuilder gathered = new StringBuilder();
        for (ChildResult result : done) {
            gathered.append("\n\n---\n\n").append(result.output());
        }
        return """
                아래 중간 산출물을 합쳐 원래 요청에 답한다.
                중간 산출물을 그대로 옮기지 말고 사용자가 읽을 답 하나로 정리한다.

                원래 요청:
                %s

                중간 산출물:%s
                """.formatted(text, gathered);
    }

    /** Chief 가 나눈 두 갈래다. 한쪽이 비면 그 단계를 건너뛴다. */
    private record Split(String research, String build) {
        boolean isEmpty() {
            return research.isBlank() && build.isBlank();
        }
    }

    /** 나란히 띄울 단계 하나다. 화면에 보일 이름과 그 단계에만 주는 지시를 갖는다. */
    private record Step(String name, String task) {
    }
}
