package com.bifos.assistant.usage.presentation;

import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/usage")
public class UsageController {

    private static final int MAX_LIMIT = 200;

    private final AgentExecutionRepository executions;
    private final CurrentUserProvider currentUser;

    public UsageController(AgentExecutionRepository executions, CurrentUserProvider currentUser) {
        this.executions = executions;
        this.currentUser = currentUser;
    }

    /** Executions of the signed-in user only. Cross-member reporting comes with the admin view. */
    @GetMapping("/executions")
    public List<ExecutionView> myExecutions(@RequestParam(defaultValue = "50") int limit) {
        int size = Math.clamp(limit, 1, MAX_LIMIT);
        return executions
                .findByUserIdOrderByIdDesc(currentUser.require().id(), PageRequest.of(0, size))
                .stream()
                .map(ExecutionView::from)
                .toList();
    }

    public record ExecutionView(
            Long id,
            Long conversationId,
            String provider,
            String model,
            String costMode,
            String status,
            String errorCode,
            Long inputTokens,
            Long cachedInputTokens,
            Long outputTokens,
            Long totalTokens,
            long latencyMs,
            Long estimatedCostMicros,
            String costCurrency,
            Instant startedAt) {

        static ExecutionView from(AgentExecution execution) {
            return new ExecutionView(
                    execution.id(),
                    execution.conversationId(),
                    execution.provider(),
                    execution.model(),
                    execution.costMode().name(),
                    execution.status().name(),
                    execution.errorCode(),
                    execution.inputTokens(),
                    execution.cachedInputTokens(),
                    execution.outputTokens(),
                    execution.totalTokens(),
                    execution.latencyMs(),
                    execution.estimatedCostMicros(),
                    execution.costCurrency(),
                    execution.startedAt());
        }
    }
}
