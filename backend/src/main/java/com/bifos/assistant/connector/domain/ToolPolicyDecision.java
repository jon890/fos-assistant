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
 * <p>판정은 DB 와 Hermes 를 모르는 {@link #decide} 하나가 한다. 조건을 위에서부터 차례로 보고 처음 맞는 것으로 정하며, 조건과
 * 그 순서는 {@link #decide} 의 코드가 갖는다. 모델이 준 인자의 내용과 서버의 {@code readOnlyHint} 는 판정에 들어가지 않는다.
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

    /** 호출이 먼저 살펴보기 트리 안에 있는지와 그 살펴보기가 쓰기 도구를 허용했는지다. 저장하지 않는다. */
    public enum CheckBoundary {
        /** 살펴보기 트리 밖의 호출이다. 보통 판정을 그대로 한다. */
        NOT_CHECK,
        /** 읽기 경계의 살펴보기다(ADR-080). 위험도가 {@code READ} 이고 승인 방식이 {@code none} 인 도구만 받는다. */
        READ_ONLY,
        /**
         * 쓰기 도구를 허용한 살펴보기다(ADR-082). 위험도가 {@code READ} 이고 승인 방식이 {@code none} 인 도구는 받고, 나머지는 상시
         * 허락을 보지 않고 승인 필요로 판정한다.
         */
        APPROVAL_ONLY
    }

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
     * @param boundary 먼저 살펴보기 트리 안의 호출인지와 그 살펴보기의 경계. 뜻은 {@link CheckBoundary} 가 갖는다
     */
    public static ToolPolicyDecision decide(
            ConnectionStatus connectionStatus,
            boolean ownServerTool,
            int schema,
            Optional<ToolPolicy> declared,
            boolean granted,
            int argsBytes,
            CheckBoundary boundary) {
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
        // 살펴보기는 사람이 보지 않는 실행이라 승인 줄을 만들지 않는다. manifest 는 READ 도구에도 required 나 always 를
        // 선언할 수 있어 위험도만 보면 승인 줄이 생기거나 상시 허락으로 통과한다. 그래서 승인 방식까지 본다.
        boolean readWithoutApproval = policy.risk() == ToolRisk.READ && policy.approval() == ToolApproval.NONE;
        if (boundary == CheckBoundary.READ_ONLY && !readWithoutApproval) {
            return denied(ActionDenyReason.READ_ONLY_RUN, policy);
        }
        if (argsBytes > MAX_ARGS_BYTES) {
            return denied(ActionDenyReason.ARGS_TOO_LARGE, policy);
        }
        // 쓰기를 허용한 살펴보기도 사람이 보지 않는 실행이라 웹 결과의 글이 쓰기를 부를 수 있다. 상시 허락과 선언의 승인 방식을 보지
        // 않고 승인 줄로 보내, 사람이 승인해야만 외부에 쓴다(ADR-082). 인자 상한은 승인 줄에 원문을 두므로 그 앞에서 본다.
        if (boundary == CheckBoundary.APPROVAL_ONLY && !readWithoutApproval) {
            return new ToolPolicyDecision(ActionDecision.NEEDS_APPROVAL, null, policy.risk(), policy.approval());
        }
        // 선언이 상시 허락을 닫은 도구는 남은 허락을 보지 않는다(ADR-065).
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
