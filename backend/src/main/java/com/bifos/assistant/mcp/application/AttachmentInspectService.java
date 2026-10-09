package com.bifos.assistant.mcp.application;

import com.bifos.assistant.chat.application.AttachmentService;
import com.bifos.assistant.chat.application.InspectedImage;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/** 기존 session 소유 판정에 현재 실행과 짧은 시각·인자 서명 경계를 더한다. */
@Service
@RequiredArgsConstructor
public class AttachmentInspectService {
    public static final String TOOL = "attachment_inspect";
    private final McpCallerResolver callers;
    private final AttachmentService attachments;
    private final AgentExecutionRepository executions;
    private final Clock clock;
    private final Map<Long, Budget> budgets = new HashMap<>();

    public InspectedImage inspect(McpPrincipal principal, JsonNode body) {
        AttachmentInspectRequest request = AttachmentInspectRequest.from(body);
        JsonNode proof = body.get("_fos_inspect");
        if (proof == null || !proof.isObject() || !proof.has("top_level") || !proof.get("top_level").isBoolean()) {
            throw invalid();
        }
        McpCaller caller = callers.resolveAttachmentInspection(principal, body.get("_fos_ctx"), proof.get("top_level").booleanValue());
        AgentExecution execution = caller.originExecution();
        requireRunning(execution);
        verify(principal, caller, request, body.get("_fos_inspect"));
        consume(caller, request.attachmentId());
        InspectedImage result = attachments.inspect(caller.user(), execution.conversationId(), request.attachmentId(), request.region(),
                () -> active(execution.id()));
        if (!active(execution.id())) {
            throw invalid();
        }
        return result;
    }

    private boolean active(Long executionId) {
        AgentExecution execution = executions.findById(executionId).orElseThrow(AttachmentInspectService::invalid);
        if (!executions.existsByIdAndStatus(executionId, ExecutionStatus.RUNNING)) {
            return false;
        }
        return execution.rootExecutionId() == null || (executions.existsById(execution.rootExecutionId())
                && !executions.existsByIdAndStatus(execution.rootExecutionId(), ExecutionStatus.CANCELLED));
    }

    private void verify(McpPrincipal principal, McpCaller caller, AttachmentInspectRequest request, JsonNode proof) {
        if (proof == null || !proof.isObject()) {
            throw invalid();
        }
        JsonNode issued = proof.get("issued_at_ms");
        JsonNode sig = proof.get("sig");
        if (issued == null || !issued.isIntegralNumber() || !issued.canConvertToLong()
                || sig == null || !sig.isTextual() || !McpCallContext.SIGNATURE.matcher(sig.asString()).matches()) {
            throw invalid();
        }
        long timestamp = issued.longValue();
        long now = clock.millis();
        if (Instant.ofEpochMilli(timestamp).isBefore(caller.originExecution().startedAt())
                || timestamp > now || timestamp < now - 60_000
                || caller.originExecution().startedAt().isBefore(clock.instant().minusSeconds(3600))) {
            throw invalid();
        }
        McpCallContext context = caller.context();
        String signed = String.join("\n", "v1-attachment-inspect", context.rootSessionId(), context.sessionId(),
                context.toolCallId(), Long.toString(timestamp), request.digest(), proof.get("top_level").booleanValue() ? "1" : "0");
        if (!MessageDigest.isEqual(McpCallContext.hmac(principal.tokenHash(), signed), HexFormat.of().parseHex(sig.asString()))) {
            throw invalid();
        }
    }

    private synchronized void consume(McpCaller caller, long attachmentId) {
        Instant now = clock.instant();
        budgets.entrySet().removeIf(entry -> entry.getValue().expires.isBefore(now));
        if (!budgets.containsKey(caller.executionId()) && budgets.size() >= 1024) {
            throw invalid();
        }
        Budget budget = budgets.computeIfAbsent(caller.executionId(), ignored -> new Budget(now.plusSeconds(3600)));
        String call = caller.context().sessionId() + "\n" + caller.context().toolCallId();
        int calls = budget.calls.getOrDefault(call, 0);
        int photoCalls = budget.photos.getOrDefault(attachmentId, 0);
        if (budget.total >= 90 || calls >= 2 || photoCalls >= 3) {
            throw invalid();
        }
        budget.calls.put(call, calls + 1);
        budget.photos.put(attachmentId, photoCalls + 1);
        budget.total++;
    }

    private static void requireRunning(AgentExecution execution) {
        if (execution.status() != ExecutionStatus.RUNNING || execution.conversationId() == null) {
            throw invalid();
        }
    }

    private static ApiException invalid() {
        return new ApiException(ErrorCode.MCP_CALL_CONTEXT_INVALID, "call context is invalid");
    }

    private static final class Budget {
        private final Instant expires;
        private final Map<String, Integer> calls = new HashMap<>();
        private final Map<Long, Integer> photos = new HashMap<>();
        private int total;

        private Budget(Instant expires) {
            this.expires = expires;
        }
    }
}
