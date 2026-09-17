package com.bifos.assistant.chat.application;

import com.bifos.assistant.chat.domain.ChatMessage;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ChatMessageRepository;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.hermes.HermesRunEventStream;
import com.bifos.assistant.hermes.HermesRunsClient;
import com.bifos.assistant.hermes.dto.HermesRunCommand;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.hermes.dto.RunEvent;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.application.ExecutionRecorder;
import com.bifos.assistant.usage.domain.AgentExecution;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
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
    public ChatTurn send(CurrentUser user, Long conversationId, String text, String agentCode) {
        PendingTurn pending = prepare(user, conversationId, text, agentCode);
        HermesRunResult result = runToCompletion(pending);
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
                    event -> forward(event, onEvent));
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

        HermesRunCommand command = new HermesRunCommand(
                agent.hermesProfile(),
                agent.apiBaseUrl(),
                text,
                null,
                conversation.hermesSessionId());
        return new PendingTurn(user, conversation, agent, command, Instant.now());
    }

    private HermesRunResult runToCompletion(PendingTurn pending) {
        try {
            return hermes.runToCompletion(pending.command());
        } catch (ApiException ex) {
            executions.recordFailure(
                    pending.user(), pending.conversation(), pending.agent(), ex.code().name(),
                    pending.startedAt(), null);
            throw ex;
        }
    }

    private String submit(PendingTurn pending) {
        try {
            return hermes.submit(pending.command());
        } catch (ApiException ex) {
            executions.recordFailure(
                    pending.user(), pending.conversation(), pending.agent(), ex.code().name(),
                    pending.startedAt(), null);
            throw ex;
        }
    }

    private HermesRunResult awaitCompletion(PendingTurn pending, String runId) {
        try {
            return hermes.awaitCompletion(pending.command(), runId);
        } catch (ApiException ex) {
            executions.recordFailure(
                    pending.user(), pending.conversation(), pending.agent(), ex.code().name(),
                    pending.startedAt(), runId);
            throw ex;
        }
    }

    private CompletedTurn finish(PendingTurn pending, HermesRunResult result) {
        if (!result.succeeded()) {
            executions.recordFailure(
                    pending.user(), pending.conversation(), pending.agent(), hermesStatus(result),
                    pending.startedAt(), result.runId());
            throw new ApiException(ErrorCode.HERMES_RUN_FAILED, "the agent run did not complete");
        }

        pending.conversation().rememberSession(result.sessionId());
        conversations.save(pending.conversation());

        AgentExecution execution = executions.recordSuccess(
                pending.user(), pending.conversation(), pending.agent(), result, pending.startedAt());
        String answer = result.output() == null ? "" : result.output();
        ChatMessage message = messages.save(
                ChatMessage.fromAssistant(pending.conversation().id(), answer, execution.id()));
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

    private static void forward(RunEvent event, Consumer<ChatEvent> onEvent) {
        String type = event.type() == null ? "" : event.type().toLowerCase();
        if (type.contains("delta") && event.text() != null) {
            onEvent.accept(ChatEvent.delta(event.text()));
        } else if (type.startsWith("tool.") || type.startsWith("subagent.")) {
            onEvent.accept(ChatEvent.tool(event.toolName(), event.detail() == null ? event.type() : event.detail()));
        }
    }

    private record PendingTurn(
            CurrentUser user,
            Conversation conversation,
            Agent agent,
            HermesRunCommand command,
            Instant startedAt) {
    }

    private record CompletedTurn(ChatTurn turn, Long messageId) {
    }
}
