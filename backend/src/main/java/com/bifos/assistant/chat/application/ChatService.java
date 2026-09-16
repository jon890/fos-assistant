package com.bifos.assistant.chat.application;

import com.bifos.assistant.chat.domain.ChatMessage;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ChatMessageRepository;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.credential.domain.HermesProfileBinding;
import com.bifos.assistant.credential.infra.HermesProfileBindingRepository;
import com.bifos.assistant.hermes.HermesRunsClient;
import com.bifos.assistant.hermes.dto.HermesRunCommand;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.application.ExecutionRecorder;
import com.bifos.assistant.usage.domain.AgentExecution;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Turns a chat message into one Hermes run and records what it cost.
 *
 * <p>Routing is decided here and nowhere else: the caller's own binding selects the profile, and a
 * caller without an active binding is refused rather than served by someone else's credential.
 */
@Service
public class ChatService {

    private static final int TITLE_LIMIT = 60;

    private final ConversationRepository conversations;
    private final ChatMessageRepository messages;
    private final HermesProfileBindingRepository bindings;
    private final HermesRunsClient hermes;
    private final ExecutionRecorder executions;

    public ChatService(
            ConversationRepository conversations,
            ChatMessageRepository messages,
            HermesProfileBindingRepository bindings,
            HermesRunsClient hermes,
            ExecutionRecorder executions) {
        this.conversations = conversations;
        this.messages = messages;
        this.bindings = bindings;
        this.hermes = hermes;
        this.executions = executions;
    }

    public ChatTurn send(CurrentUser user, Long conversationId, String text) {
        HermesProfileBinding binding = requireActiveBinding(user);
        Conversation conversation = resolveConversation(user, conversationId, text);
        messages.save(ChatMessage.fromUser(conversation.id(), text));

        Instant startedAt = Instant.now();
        HermesRunResult result;
        try {
            result =
                    hermes.runToCompletion(
                            new HermesRunCommand(
                                    binding.profileName(), text, null, conversation.hermesSessionId()));
        } catch (ApiException ex) {
            executions.recordFailure(user, conversation, binding, ex.code().name(), startedAt);
            throw ex;
        }

        if (!result.succeeded()) {
            executions.recordFailure(user, conversation, binding, hermesStatus(result), startedAt);
            throw new ApiException(ErrorCode.HERMES_RUN_FAILED, "the agent run did not complete");
        }

        conversation.rememberSession(result.sessionId());
        conversations.save(conversation);

        AgentExecution execution = executions.recordSuccess(user, conversation, binding, result, startedAt);
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

    private HermesProfileBinding requireActiveBinding(CurrentUser user) {
        HermesProfileBinding binding =
                bindings
                        .findByUserId(user.id())
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                ErrorCode.HERMES_BINDING_MISSING,
                                                "no AI account is connected to this user yet"));
        if (!binding.isActive()) {
            throw new ApiException(
                    ErrorCode.HERMES_BINDING_DISABLED, "the AI account connected to this user is disabled");
        }
        return binding;
    }

    private Conversation resolveConversation(CurrentUser user, Long conversationId, String firstText) {
        if (conversationId == null) {
            return conversations.save(Conversation.startedBy(user.id(), titleFrom(firstText)));
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
}
