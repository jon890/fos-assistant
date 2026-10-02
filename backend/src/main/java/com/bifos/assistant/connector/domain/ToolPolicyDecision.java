package com.bifos.assistant.connector.domain;

import com.bifos.assistant.connector.domain.type.ActionDecision;
import com.bifos.assistant.connector.domain.type.ActionDenyReason;
import com.bifos.assistant.connector.domain.type.ConnectionStatus;
import com.bifos.assistant.connector.domain.type.ToolApproval;
import com.bifos.assistant.connector.domain.type.ToolRisk;
import java.util.Optional;

/**
 * 커넥터 도구 호출 하나의 판정이다(ADR-049).
 *
 * <p>판정은 DB 와 Hermes 를 모르는 {@link #decide} 하나가 한다. 순서는 {@code docs/backend/connector-tool-policy.md} 의 「도구 호출
 * 판정」 표와 같다. 모델이 준 인자의 내용과 서버의 {@code readOnlyHint} 는 판정에 들어가지 않는다.
 *
 * @param denyReason {@code DENIED} 일 때만 있다
 * @param risk 판정에 쓴 위험도. 정책을 읽지 못했거나 연결이 준비되지 않았거나 선언이 없어 거절한 호출은 null
 * @param approval 판정에 쓴 승인 방식. 위와 같을 때 null
 */
public record ToolPolicyDecision(
        ActionDecision decision, ActionDenyReason denyReason, ToolRisk risk, ToolApproval approval) {
    /** 승인 줄에 원문으로 둘 수 있는 인자 글의 UTF-8 바이트 상한이다. */
    public static final int MAX_ARGS_BYTES = 16 * 1024;

    /** 도구를 선언하지 않는 manifest 판이다. */
    private static final int LEGACY_SCHEMA = 1;

    /** manifest 를 읽지 못했을 때다. */
    public static ToolPolicyDecision policyUnavailable() {
        return denied(ActionDenyReason.POLICY_UNAVAILABLE, null);
    }

    /**
     * @param ownServerTool 등록 이름이 그 커넥터의 MCP 서버가 낸 도구의 것인가. 아니면 판과 상관없이 선언 없는 도구다
     * @param schema manifest 의 판
     * @param declared 그 도구의 선언. manifest 에 없으면 빈 값
     * @param granted 그 도구에 유효한 상시 허락이 있는가
     * @param argsBytes 인자 글의 UTF-8 바이트 수
     */
    public static ToolPolicyDecision decide(
            ConnectionStatus connectionStatus,
            boolean ownServerTool,
            int schema,
            Optional<ToolPolicy> declared,
            boolean granted,
            int argsBytes) {
        if (connectionStatus != ConnectionStatus.READY) {
            return denied(ActionDenyReason.NOT_READY, null);
        }
        // 도구를 선언하지 않는 판이 쓰기로 읽어 주는 것은 그 커넥터의 MCP 서버가 낸 도구뿐이다.
        if (!ownServerTool || (declared.isEmpty() && schema != LEGACY_SCHEMA)) {
            return denied(ActionDenyReason.UNDECLARED, null);
        }
        // 도구를 선언하지 않는 판의 선언 없는 도구는 쓰기로 읽는다. 무엇을 하는지 모르므로 읽기로 풀어 주지 않는다.
        // 그 판은 상시 허락을 닫는 선언을 둘 수 없어 허락을 줄 수 있는 것으로 둔다.
        ToolPolicy policy = declared.orElseGet(() -> new ToolPolicy(ToolRisk.WRITE, ToolApproval.REQUIRED, null, true));
        if (policy.risk() == ToolRisk.DESTRUCTIVE || policy.risk() == ToolRisk.FINANCIAL) {
            return denied(ActionDenyReason.RISK_NOT_OPEN, policy);
        }
        if (argsBytes > MAX_ARGS_BYTES) {
            return denied(ActionDenyReason.ARGS_TOO_LARGE, policy);
        }
        // 선언이 상시 허락을 닫은 도구는 남은 허락을 보지 않는다(ADR-063).
        if (policy.approval() == ToolApproval.NONE
                || (policy.approval() == ToolApproval.REQUIRED && granted && policy.grantable())) {
            return new ToolPolicyDecision(ActionDecision.ALLOWED, null, policy.risk(), policy.approval());
        }
        return new ToolPolicyDecision(ActionDecision.NEEDS_APPROVAL, null, policy.risk(), policy.approval());
    }

    private static ToolPolicyDecision denied(ActionDenyReason reason, ToolPolicy policy) {
        return new ToolPolicyDecision(
                ActionDecision.DENIED,
                reason,
                policy == null ? null : policy.risk(),
                policy == null ? null : policy.approval());
    }
}
