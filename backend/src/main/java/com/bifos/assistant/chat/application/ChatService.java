package com.bifos.assistant.chat.application;

import com.bifos.assistant.chat.domain.ChatMessage;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ChatMessageRepository;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.context.AssembledContext;
import com.bifos.assistant.context.ContextAssembler;
import com.bifos.assistant.memory.application.MemoryProposer;
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
import java.util.List;
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
 */
@Service
@RequiredArgsConstructor
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final int TITLE_LIMIT = 60;

    private final ConversationRepository conversations;
    private final ChatMessageRepository messages;
    private final AgentService agents;
    private final HermesRunsClient hermes;
    private final HermesRunEventStream eventStream;
    private final ExecutionRecorder executions;
    private final ExecutionEventRecorder eventRecorder;
    private final ExecutionEventRepository executionEvents;
    private final ContextAssembler contextAssembler;
    private final MemoryProposer memoryProposer;

    public ChatTurn send(CurrentUser user, Long conversationId, String text, String agentCode) {
        PendingTurn pending = prepare(user, conversationId, text, agentCode);
        String runId = submit(pending);
        HermesRunResult result = awaitCompletion(pending, runId);
        return finish(pending, result).turn();
    }

    public void stream(
            CurrentUser user,
            Long conversationId,
            String text,
            String agentCode,
            Consumer<ChatEvent> onEvent) {
        PendingTurn pending = prepare(user, conversationId, text, agentCode);
        String runId = submit(pending);
        try {
            eventStream.open(
                    pending.command().apiBaseUrl(),
                    pending.command().profileName(),
                    runId,
                    event -> forward(pending, event, onEvent));
        } catch (ApiException ex) {
            log.warn("Hermes event stream ended before final status runId={}", runId, ex);
        }

        HermesRunResult result = awaitCompletion(pending, runId);
        CompletedTurn completed = finish(pending, result);
        onEvent.accept(ChatEvent.done(
                completed.turn().conversationId(),
                completed.messageId(),
                completed.turn().executionId()));
    }

    private PendingTurn prepare(
            CurrentUser user, Long conversationId, String text, String agentCode) {
        Conversation conversation = resolveConversation(user, conversationId, text, agentCode);
        Agent agent = agents.requireById(conversation.agentId());
        if (!agent.enabled()) {
            throw new ApiException(ErrorCode.AGENT_DISABLED, "this agent is disabled");
        }
        messages.save(ChatMessage.fromUser(conversation.id(), user.id(), text));

        AssembledContext context = contextAssembler.assemble(user);
        HermesRunCommand command = new HermesRunCommand(
                agent.hermesProfile(),
                agent.apiBaseUrl(),
                text,
                context.instructions(),
                conversation.hermesSessionId());
        AgentExecution execution = executions.start(user, conversation, agent, null, null,
                new ExecutionContextSnapshot(
                        context.chars(), null, context.instructionsHash(), context.omittedItems()));
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

    private HermesRunResult awaitCompletion(PendingTurn pending, String runId) {
        try {
            return hermes.awaitCompletion(pending.command(), runId);
        } catch (ApiException ex) {
            executions.fail(pending.execution(), ex.code().name());
            append(pending, ExecutionEventType.RUN_FAILED, ex.code().name());
            throw ex;
        }
    }

    private CompletedTurn finish(PendingTurn pending, HermesRunResult result) {
        if (!result.succeeded()) {
            executions.fail(pending.execution(), hermesStatus(result));
            append(pending, ExecutionEventType.RUN_FAILED, hermesStatus(result));
            throw new ApiException(ErrorCode.HERMES_RUN_FAILED, "the agent run did not complete");
        }

        pending.conversation().rememberSession(result.sessionId());
        conversations.save(pending.conversation());

        AgentExecution execution = executions.complete(pending.execution(), pending.agent(), result);
        append(pending, ExecutionEventType.RUN_COMPLETED, null);
        String answer = result.output() == null ? "" : result.output();
        ChatMessage message = messages.save(
                ChatMessage.fromAssistant(pending.conversation().id(), answer, execution.id()));
        memoryProposer.proposeFrom(pending.user(), pending.conversation(), pending.agent(), execution, answer);
        return new CompletedTurn(
                new ChatTurn(pending.conversation().id(), execution.id(), answer), message.id());
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

    private record CompletedTurn(ChatTurn turn, Long messageId) {
    }
}
