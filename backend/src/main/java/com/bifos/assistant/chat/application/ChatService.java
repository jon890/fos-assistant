package com.bifos.assistant.chat.application;

import com.bifos.assistant.chat.domain.ChatMessage;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ChatMessageRepository;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.hermes.HermesRunsClient;
import com.bifos.assistant.hermes.dto.HermesRunCommand;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.application.ExecutionRecorder;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.workspace.application.WorkspaceService;
import com.bifos.assistant.workspace.domain.Workspace;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Turns a chat message into one Hermes run and records what it cost.
 *
 * <p>Routing is decided here and nowhere else: the caller's own binding selects the profile, and a
 * caller without an active binding is refused rather than served by someone else's credential.
 */
@Service
@RequiredArgsConstructor
public class ChatService {

    private static final int TITLE_LIMIT = 60;

    private final ConversationRepository conversations;
    private final ChatMessageRepository messages;
    private final AgentService agents;
    private final HermesRunsClient hermes;
    private final ExecutionRecorder executions;
    private final WorkspaceService workspaces;

    public ChatTurn send(CurrentUser user, Long conversationId, String text, String workspaceCode,
            String agentCode) {
        Conversation conversation = resolveConversation(user, conversationId, text, workspaceCode, agentCode);
        Agent agent = agents.requireById(conversation.agentId());
        if (!agent.enabled()) {
            throw new ApiException(ErrorCode.AGENT_DISABLED, "this agent is disabled");
        }
        messages.save(ChatMessage.fromUser(conversation.id(), user.id(), text));

        // 이어지는 대화는 그 대화에 기록된 영역을 쓴다. 요청 본문이 중간에 영역을 바꾸지 못한다.
        Workspace workspace = workspaces.findByIdOrNull(conversation.workspaceId());
        String instructions = workspace == null ? null : workspaces.briefing(workspace);

        Instant startedAt = Instant.now();
        HermesRunResult result;
        try {
            result =
                    hermes.runToCompletion(
                            new HermesRunCommand(
                                    agent.hermesProfile(),
                                    agent.apiBaseUrl(),
                                    text,
                                    instructions,
                                    conversation.hermesSessionId()));
        } catch (ApiException ex) {
            executions.recordFailure(user, conversation, agent, ex.code().name(), startedAt);
            throw ex;
        }

        if (!result.succeeded()) {
            executions.recordFailure(user, conversation, agent, hermesStatus(result), startedAt);
            throw new ApiException(ErrorCode.HERMES_RUN_FAILED, "the agent run did not complete");
        }

        conversation.rememberSession(result.sessionId());
        conversations.save(conversation);

        AgentExecution execution = executions.recordSuccess(user, conversation, agent, result, startedAt);
        String answer = result.output() == null ? "" : result.output();
        messages.save(ChatMessage.fromAssistant(conversation.id(), answer, execution.id()));
        return new ChatTurn(conversation.id(), execution.id(), answer);
    }

    public List<ChatMessage> history(CurrentUser user, Long conversationId) {
        Conversation conversation = requireOwnConversation(user, conversationId);
        return messages.findByConversationIdOrderByIdAsc(conversation.id());
    }

    public List<Conversation> conversationsOf(CurrentUser user) {
        return conversations.findByUserIdOrderByUpdatedAtDesc(user.id());
    }

    private Conversation resolveConversation(
            CurrentUser user, Long conversationId, String firstText, String workspaceCode,
            String agentCode) {
        if (conversationId == null) {
            Long workspaceId = resolveNewWorkspaceId(user, workspaceCode);
            if (agentCode == null || agentCode.isBlank()) {
                throw new ApiException(ErrorCode.AGENT_NOT_FOUND, "an agent is required");
            }
            Agent agent = agents.requireReadable(user, agentCode);
            if (!agent.enabled()) {
                throw new ApiException(ErrorCode.AGENT_DISABLED, "this agent is disabled");
            }
            return conversations.save(
                    Conversation.startedBy(user.id(), titleFrom(firstText), workspaceId, agent.id()));
        }
        return requireOwnConversation(user, conversationId);
    }

    /** No code means no workspace. It never falls back to a default; that default would be a leak. */
    private Long resolveNewWorkspaceId(CurrentUser user, String workspaceCode) {
        if (workspaceCode == null || workspaceCode.isBlank()) {
            return null;
        }
        return workspaces.requireReadable(user, workspaceCode).id();
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
}
