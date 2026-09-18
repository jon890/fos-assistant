package com.bifos.assistant.chat.application;

import com.bifos.assistant.chat.domain.ChatMessage;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ChatMessageRepository;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.agent.application.AgentModelSelector;
import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.application.ProviderBlocklist;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.ModelOption;
import com.bifos.assistant.context.AssembledContext;
import com.bifos.assistant.context.ContextAssembler;
import com.bifos.assistant.memory.application.MemoryProposer;
import com.bifos.assistant.orchestration.application.Flow;
import com.bifos.assistant.orchestration.application.FlowRegistry;
import com.bifos.assistant.hermes.HermesRunEventStream;
import com.bifos.assistant.hermes.HermesRunsClient;
import com.bifos.assistant.hermes.dto.HermesRunCommand;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.hermes.dto.RunEvent;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.application.ExecutionContextSnapshot;
import com.bifos.assistant.usage.application.ExecutionEventRecorder;
import com.bifos.assistant.usage.application.ExecutionRecorder;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.ExecutionEvent;
import com.bifos.assistant.usage.domain.ExecutionEventType;
import com.bifos.assistant.usage.infra.ExecutionEventRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 대화 메시지 하나를 Hermes 실행으로 바꾸고 비용을 기록한다.
 *
 * <p>라우팅은 여기에서만 정한다. 대화의 에이전트가 profile을 고르고, 대화가 시작된 뒤 요청 본문은
 * 그 profile을 바꾸지 못한다.
 *
 * <p>쓸 모델도 여기에서 고른다. 에이전트의 모델 목록을 순위대로 시도하고, 그 provider 의 계정이 전부
 * 막히면 그 턴 안에서 다음 순위로 다시 보낸다. 한 provider 안에서 계정을 돌려 쓰는 것은 Hermes 가
 * 이미 하므로 여기서 하지 않는다.
 */
@Service
@RequiredArgsConstructor
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final int TITLE_LIMIT = 60;

    private final ConversationRepository conversations;
    private final ChatMessageRepository messages;
    private final AgentService agents;
    private final AgentModelSelector modelSelector;
    private final ProviderBlocklist blocklist;
    private final HermesRunsClient hermes;
    private final HermesRunEventStream eventStream;
    private final ExecutionRecorder executions;
    private final ExecutionEventRecorder eventRecorder;
    private final ExecutionEventRepository executionEvents;
    private final ContextAssembler contextAssembler;
    private final MemoryProposer memoryProposer;
    private final FlowRegistry flows;

    public ChatTurn send(CurrentUser user, Long conversationId, String text, String agentCode) {
        Routed routed = route(user, conversationId, text, agentCode);
        if (routed.flow() != null) {
            return routed.flow().run(user, routed.conversation(), routed.agent(), text, event -> {});
        }
        return runTurn(user, routed, text, event -> {}, false);
    }

    public void stream(
            CurrentUser user,
            Long conversationId,
            String text,
            String agentCode,
            Consumer<ChatEvent> onEvent) {
        Routed routed = route(user, conversationId, text, agentCode);
        if (routed.flow() != null) {
            streamFlow(user, routed, text, onEvent);
            return;
        }
        ChatTurn turn = runTurn(user, routed, text, onEvent, true);
        onEvent.accept(
                ChatEvent.done(turn.conversationId(), turn.messageId(), turn.executionId()));
    }

    /**
     * 쓸 수 있는 모델을 순위대로 시도해 turn 하나를 끝낸다.
     *
     * <p>막혀서 실패한 실행도 지우지 않고 {@code FAILED} 로 남긴다. 다음 시도는 새 실행이고
     * {@code retryOfExecutionId} 로 직전 실행을 가리킨다. 그래야 무엇이 얼마나 막혔는지 나중에 볼 수
     * 있다.
     *
     * <p>넘김은 그 provider 의 계정이 전부 막혔을 때만 한다. 모델 이름이 틀렸거나 입력이 잘못된 것은
     * 다음 provider 에서도 똑같이 실패하므로 넘기면 같은 실패를 목록 수만큼 되풀이한다.
     */
    private ChatTurn runTurn(
            CurrentUser user, Routed routed, String text, Consumer<ChatEvent> onEvent, boolean streaming) {
        Conversation conversation = routed.conversation();
        Agent agent = routed.agent();
        messages.save(ChatMessage.fromUser(conversation.id(), user.id(), text));
        AssembledContext context = contextAssembler.assemble(user);
        ExecutionContextSnapshot snapshot = new ExecutionContextSnapshot(
                context.chars(), null, context.instructionsHash(), context.omittedItems());

        List<ModelOption> options = modelSelector.availableFor(agent);
        if (options.isEmpty()) {
            throw noModelAvailable(user, routed, snapshot);
        }

        Long previousExecutionId = null;
        for (int index = 0; index < options.size(); index++) {
            ModelOption option = options.get(index);
            boolean last = index == options.size() - 1;
            PendingTurn pending = begin(user, routed, text, context, snapshot, option, previousExecutionId);
            if (previousExecutionId != null) {
                append(pending, ExecutionEventType.PROVIDER_SWITCHED, option.label());
                onEvent.accept(ChatEvent.switched(option.label()));
            }

            String runId = submit(pending);
            if (streaming) {
                relay(pending, runId, onEvent);
            }
            HermesRunResult result = awaitCompletion(pending, runId);

            if (result.succeeded()) {
                blocklist.release(option.provider());
                return finish(pending, result, option);
            }
            if (!result.providerBlocked()) {
                executions.fail(pending.execution(), hermesStatus(result));
                append(pending, ExecutionEventType.RUN_FAILED, hermesStatus(result));
                throw new ApiException(ErrorCode.HERMES_RUN_FAILED, "the agent run did not complete");
            }

            blocklist.block(option.provider(), "provider authentication failed");
            executions.fail(pending.execution(), ErrorCode.PROVIDER_BLOCKED.name());
            append(pending, ExecutionEventType.RUN_FAILED, ErrorCode.PROVIDER_BLOCKED.name());
            if (streaming) {
                onEvent.accept(ChatEvent.reset());
            }
            if (last) {
                throw new ApiException(
                        ErrorCode.NO_MODEL_AVAILABLE, "every model this agent can use is blocked");
            }
            log.info("provider 가 막혀 다음 모델로 넘어간다 agent={} blocked={}", agent.code(), option.provider());
            previousExecutionId = pending.execution().id();
        }
        throw new ApiException(ErrorCode.NO_MODEL_AVAILABLE, "every model this agent can use is blocked");
    }

    /**
     * 쓸 수 있는 모델이 하나도 없다는 것을 실행 한 줄로 남기고 세운다.
     *
     * <p>Hermes 를 부르지 않는다. 실행 줄을 남기는 것은 실패해도 기록은 남긴다는 규칙 때문이고, 그것이
     * 없으면 사용량 화면에서 이 turn 이 통째로 사라진다.
     */
    private ApiException noModelAvailable(
            CurrentUser user, Routed routed, ExecutionContextSnapshot snapshot) {
        AgentExecution execution = executions.start(
                user, routed.conversation(), routed.agent(), null, null, snapshot, null, null);
        PendingTurn pending = new PendingTurn(
                user, routed.conversation(), routed.agent(), null, execution, new SequenceCounter());
        executions.fail(execution, ErrorCode.NO_MODEL_AVAILABLE.name());
        append(pending, ExecutionEventType.RUN_FAILED, ErrorCode.NO_MODEL_AVAILABLE.name());
        return new ApiException(
                ErrorCode.NO_MODEL_AVAILABLE, "this agent has no model it can use right now");
    }

    /**
     * 흐름으로 도는 turn 을 중계한다.
     *
     * <p>흐름은 단계 사건만 흘리고 답은 끝난 뒤에 한 번에 온다. 중간 단계의 답까지 흘리면 읽을 수
     * 없기 때문이다. 근거는 ADR-016 에 있다.
     */
    private void streamFlow(
            CurrentUser user, Routed routed, String text, Consumer<ChatEvent> onEvent) {
        ChatTurn turn = routed.flow().run(user, routed.conversation(), routed.agent(), text, onEvent);
        onEvent.accept(ChatEvent.delta(turn.assistantText()));
        onEvent.accept(
                ChatEvent.done(turn.conversationId(), turn.messageId(), turn.executionId()));
    }

    /**
     * 대화와 에이전트를 정하고 어느 경로로 갈지 고른다.
     *
     * <p>에이전트에 {@code flow} 가 적혀 있으면 그 흐름으로 간다. 비어 있으면 지금처럼 Hermes 를 한
     * 번 부른다. 모르는 이름은 기동할 때 이미 걸러졌다.
     */
    private Routed route(CurrentUser user, Long conversationId, String text, String agentCode) {
        Conversation conversation = resolveConversation(user, conversationId, text, agentCode);
        Agent agent = agents.requireById(conversation.agentId());
        if (!agent.enabled()) {
            throw new ApiException(ErrorCode.AGENT_DISABLED, "this agent is disabled");
        }
        return new Routed(conversation, agent, flows.find(agent.flow()));
    }

    /** 시도 하나를 위한 명령과 실행 줄을 만든다. 사용자 메시지는 이미 저장돼 있다. */
    private PendingTurn begin(
            CurrentUser user,
            Routed routed,
            String text,
            AssembledContext context,
            ExecutionContextSnapshot snapshot,
            ModelOption option,
            Long retryOfExecutionId) {
        Conversation conversation = routed.conversation();
        Agent agent = routed.agent();
        HermesRunCommand command = new HermesRunCommand(
                agent.hermesProfile(),
                agent.apiBaseUrl(),
                text,
                context.instructions(),
                conversation.hermesSessionId(),
                option.provider(),
                option.model());
        AgentExecution execution = executions.start(
                user, conversation, agent, null, null, snapshot, option, retryOfExecutionId);
        return new PendingTurn(user, conversation, agent, command, execution, new SequenceCounter());
    }

    private String submit(PendingTurn pending) {
        try {
            String runId = hermes.submit(pending.command());
            executions.attachRunId(pending.execution(), runId);
            append(pending, ExecutionEventType.RUN_STARTED, null);
            return runId;
        } catch (ApiException ex) {
            executions.fail(pending.execution(), ex.code().name());
            append(pending, ExecutionEventType.RUN_FAILED, ex.code().name());
            throw ex;
        }
    }

    private void relay(PendingTurn pending, String runId, Consumer<ChatEvent> onEvent) {
        try {
            eventStream.open(
                    pending.command().apiBaseUrl(),
                    pending.command().profileName(),
                    runId,
                    event -> forward(pending, event, onEvent));
        } catch (ApiException ex) {
            log.warn("Hermes event stream ended before final status runId={}", runId, ex);
        }
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

    private ChatTurn finish(PendingTurn pending, HermesRunResult result, ModelOption requested) {
        pending.conversation().rememberSession(result.sessionId());
        conversations.save(pending.conversation());

        AgentExecution execution =
                executions.complete(pending.execution(), pending.agent(), result, requested);
        append(pending, ExecutionEventType.RUN_COMPLETED, null);
        String answer = result.output() == null ? "" : result.output();
        ChatMessage message = messages.save(
                ChatMessage.fromAssistant(pending.conversation().id(), answer, execution.id()));
        memoryProposer.proposeFrom(pending.user(), pending.conversation(), pending.agent(), execution, answer);
        return new ChatTurn(pending.conversation().id(), execution.id(), answer, message.id());
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
        for (ExecutionEvent event :
                executionEvents.findByExecutionIdInOrderByExecutionIdAscSequenceAsc(executionIds)) {
            if (event.eventType() == ExecutionEventType.PROVIDER_SWITCHED && event.detail() != null) {
                labels.put(event.executionId(), event.detail());
            }
        }
        return labels;
    }

    public List<ChatMessage> history(CurrentUser user, Long conversationId) {
        Conversation conversation = requireOwnConversation(user, conversationId);
        return messages.findByConversationIdOrderByIdAsc(conversation.id());
    }

    public List<Conversation> conversationsOf(CurrentUser user) {
        return conversations.findByUserIdOrderByUpdatedAtDesc(user.id());
    }

    private Conversation resolveConversation(
            CurrentUser user, Long conversationId, String firstText, String agentCode) {
        if (conversationId == null) {
            if (agentCode == null || agentCode.isBlank()) {
                throw new ApiException(ErrorCode.AGENT_NOT_FOUND, "an agent is required");
            }
            Agent agent = agents.requireReadable(user, agentCode);
            if (!agent.enabled()) {
                throw new ApiException(ErrorCode.AGENT_DISABLED, "this agent is disabled");
            }
            return conversations.save(
                    Conversation.startedBy(user.id(), titleFrom(firstText), agent.id()));
        }
        return requireOwnConversation(user, conversationId);
    }

    private Conversation requireOwnConversation(CurrentUser user, Long conversationId) {
        return conversations
                .findByIdAndUserId(conversationId, user.id())
                .orElseThrow(
                        () ->
                                new ApiException(
                                        ErrorCode.CONVERSATION_NOT_FOUND, "this conversation does not exist"));
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
        if (type.contains("delta") && event.text() != null) {
            onEvent.accept(ChatEvent.delta(event.text()));
        } else if (type.startsWith("tool.") || type.startsWith("subagent.")) {
            onEvent.accept(ChatEvent.tool(event.toolName(), event.detail() == null ? event.type() : event.detail()));
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
            log.warn("could not record an execution event executionId={}", pending.execution().id(), ex);
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
            SequenceCounter counter) {
    }

    /** 이 turn 이 어느 대화와 에이전트의 것인지, 그리고 어느 흐름으로 갈지. 흐름이 없으면 null 이다. */
    private record Routed(Conversation conversation, Agent agent, Flow flow) {
    }
}
