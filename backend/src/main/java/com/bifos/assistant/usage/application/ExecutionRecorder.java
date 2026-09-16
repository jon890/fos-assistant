package com.bifos.assistant.usage.application;

import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.credential.domain.HermesProfileBinding;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.hermes.dto.TokenUsage;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;

/** Writes one row per agent turn so usage stays attributable to a user, a model and a provider. */
@Service
public class ExecutionRecorder {

    private final AgentExecutionRepository executions;

    public ExecutionRecorder(AgentExecutionRepository executions) {
        this.executions = executions;
    }

    public AgentExecution recordSuccess(
            CurrentUser user,
            Conversation conversation,
            HermesProfileBinding binding,
            HermesRunResult result,
            Instant startedAt) {
        TokenUsage usage = result.usage() == null ? TokenUsage.empty() : result.usage();
        return executions.save(
                base(user, conversation, binding, startedAt)
                        .hermesRunId(result.runId())
                        .provider(firstNonBlank(result.provider(), binding.provider()))
                        .model(firstNonBlank(result.model(), binding.model()))
                        .status(ExecutionStatus.SUCCEEDED)
                        .tokens(
                                usage.inputTokens(),
                                usage.cachedInputTokens(),
                                usage.outputTokens(),
                                usage.totalTokens())
                        .build());
    }

    public AgentExecution recordFailure(
            CurrentUser user,
            Conversation conversation,
            HermesProfileBinding binding,
            String errorCode,
            Instant startedAt) {
        return executions.save(
                base(user, conversation, binding, startedAt)
                        .provider(binding.provider())
                        .model(binding.model())
                        .status(ExecutionStatus.FAILED)
                        .errorCode(errorCode)
                        .build());
    }

    private AgentExecution.Builder base(
            CurrentUser user, Conversation conversation, HermesProfileBinding binding, Instant startedAt) {
        return AgentExecution.builder()
                .userId(user.id())
                .conversationId(conversation.id())
                .profileName(binding.profileName())
                .costMode(binding.costMode())
                .timing(startedAt, Instant.now());
    }

    private static String firstNonBlank(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred;
    }
}
