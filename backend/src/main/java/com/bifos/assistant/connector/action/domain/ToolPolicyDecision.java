package com.bifos.assistant.connector.action.domain;

import com.bifos.assistant.connector.connection.domain.ToolPolicy;
import com.bifos.assistant.connector.action.domain.type.ActionDecision;
import com.bifos.assistant.connector.action.domain.type.ActionDenyReason;
import com.bifos.assistant.connector.connection.domain.type.ConnectionStatus;
import com.bifos.assistant.connector.connection.domain.type.ToolApproval;
import com.bifos.assistant.connector.connection.domain.type.ToolRisk;
import java.util.Optional;

/**
 * 커넥터 도구 호출 하나의 판정이다(ADR-049).
 *
 * <p>판정은 DB 와 Hermes 를 모르는 {@link #decide} 하나가 한다. 조건을 위에서부터 차례로 보고 처음 맞는 것으로 정하며, 조건과
 * 그 순서는 {@link #decide} 의 코드가 갖는다. 모델이 준 인자의 내용은 판정에 들어가지 않는다. 서버의 {@code readOnlyHint} 는 표준
 * 플러그인의 선언 없는 도구에 상시 허락을 열지만 승인 필요를 풀지 않는다(ADR-20261101 / plugin-without-assistant-json).
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

    /** {@code assistant.json} 이 없어 도구를 선언하지 않는 표준 플러그인의 schema 값이다(ADR-20261101 / plugin-without-assistant-json). */
    private static final int STANDARD_SCHEMA = 3;

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


    /** 대화 허락이 없는 호출이다. 뜻은 {@link #decide(ConnectionStatus, boolean, int, Optional, boolean, boolean, int, CheckBoundary)} 와 같다. */
    public static ToolPolicyDecision decide(
        ConnectionStatus connectionStatus,
        boolean ownServerTool,
        int schema,
        Optional<ToolPolicy> declared,
        boolean granted,
        int argsBytes,
        CheckBoundary boundary) {
        return decide(connectionStatus, ownServerTool, schema, declared, granted, false, argsBytes, boundary);
    }


    /** 읽기 표시를 보지 않는 호출이다. 뜻은 {@link #decide(ConnectionStatus, boolean, int, Optional, boolean, boolean, boolean, int, CheckBoundary)} 와 같다. */
    public static ToolPolicyDecision decide(
        ConnectionStatus connectionStatus,
        boolean ownServerTool,
        int schema,
        Optional<ToolPolicy> declared,
        boolean granted,
        boolean conversationGranted,
        int argsBytes,
        CheckBoundary boundary) {
        return decide(connectionStatus, ownServerTool, schema, declared, granted, conversationGranted, false, argsBytes, boundary);
    }


    /**
     * @param ownServerTool 등록 이름이 그 커넥터의 MCP 서버가 낸 도구의 것인가. 아니면 판과 상관없이 선언 없는 도구다
     * @param schema manifest 의 판
     * @param declared 그 도구의 선언. manifest 에 없으면 빈 값
     * @param granted 그 도구에 유효한 상시 허락이 있는가
     * @param conversationGranted 그 대화의 그 에이전트 실행에 그 도구의 유효한 대화 허락이 있는가
     * @param readOnlyMarked 표준 플러그인 연결의 마지막 확인에서 그 도구가 읽기 표시({@code readOnlyHint})를 받았는가. 표준 플러그인의
     * 선언 없는 도구에만 쓴다
     * @param argsBytes 인자 글의 UTF-8 바이트 수
     * @param boundary 먼저 살펴보기 트리 안의 호출인지와 그 살펴보기의 경계. 뜻은 {@link CheckBoundary} 가 갖는다
     */
    public static ToolPolicyDecision decide(
        ConnectionStatus connectionStatus,
        boolean ownServerTool,
        int schema,
        Optional<ToolPolicy> declared,
        boolean granted,
        boolean conversationGranted,
        boolean readOnlyMarked,
        int argsBytes,
        CheckBoundary boundary) {
        if (connectionStatus != ConnectionStatus.READY) {
            return denied(ActionDenyReason.NOT_READY, null);
        }
        // 도구를 선언하지 않는 판이 쓰기로 읽어 주는 것은 그 커넥터의 MCP 서버가 낸 도구뿐이다.
        if (!ownServerTool || (declared.isEmpty() && schema != LEGACY_SCHEMA && schema != STANDARD_SCHEMA)) {
            return denied(ActionDenyReason.UNDECLARED, null);
        }
        // 도구를 선언하지 않는 판의 선언 없는 도구는 쓰기로 읽는다. 무엇을 하는지 모르므로 읽기로 풀어 주지 않는다.
        // `schema: 1` 은 상시 허락을 닫는 선언을 둘 수 없어 허락을 줄 수 있는 것으로 둔다. 표준 플러그인은 운영자가 읽어 본 선언이
        // 없어 서버가 읽기 표시를 단 도구만 상시 허락을 열고, 나머지는 호출마다 사람이 본다. 읽기 표시가 있어도 첫 호출은 승인이다.
        // 대화 허락은 여는 선언이 없어 둘 다 닫는다.
        boolean grantable = schema == LEGACY_SCHEMA || (schema == STANDARD_SCHEMA && readOnlyMarked);
        ToolPolicy policy = declared.orElseGet(
            () -> new ToolPolicy(ToolRisk.WRITE, ToolApproval.REQUIRED, null, grantable));
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
        // 선언이 상시 허락을 닫은 도구는 남은 허락을 보지 않는다(ADR-065). 대화 허락은 선언이 연 도구에서만 본다
        // (ADR-20261012 / conversation-grant).
        boolean allowedByGrant = (granted && policy.grantable())
            || (conversationGranted && policy.conversationGrantable());
        if (policy.approval() == ToolApproval.NONE
            || (policy.approval() == ToolApproval.REQUIRED && allowedByGrant)) {
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
