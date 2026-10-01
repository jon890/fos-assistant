package com.bifos.assistant.connector;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.connector.domain.ToolPolicy;
import com.bifos.assistant.connector.domain.ToolPolicyDecision;
import com.bifos.assistant.connector.domain.type.ActionDecision;
import com.bifos.assistant.connector.domain.type.ActionDenyReason;
import com.bifos.assistant.connector.domain.type.ConnectionStatus;
import com.bifos.assistant.connector.domain.type.ToolApproval;
import com.bifos.assistant.connector.domain.type.ToolRisk;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 판정 순서는 {@code docs/connectors.md} 의 「도구 호출 판정」 표다. */
class ToolPolicyDecisionTest {
    private static final Optional<ToolPolicy> READ = Optional.of(new ToolPolicy(ToolRisk.READ, ToolApproval.NONE, null));
    private static final Optional<ToolPolicy> WRITE =
            Optional.of(new ToolPolicy(ToolRisk.WRITE, ToolApproval.REQUIRED, null));

    @Test
    @DisplayName("manifest 를 읽지 못한 판정은 POLICY_UNAVAILABLE 거절이고 위험도와 승인 방식이 비어 있다")
    void policyUnavailableIsDeniedWithoutRiskAndApproval() {
        assertThat(ToolPolicyDecision.policyUnavailable())
                .isEqualTo(new ToolPolicyDecision(
                        ActionDecision.DENIED, ActionDenyReason.POLICY_UNAVAILABLE, null, null));
    }

    @Test
    @DisplayName("연결이 READY 가 아니면 읽기 도구여도 NOT_READY 로 거절하고 위험도를 비운다")
    void connectionThatIsNotReadyIsDeniedBeforeAnythingElse() {
        for (ConnectionStatus status : new ConnectionStatus[] {ConnectionStatus.PENDING, ConnectionStatus.DISCONNECTED
        }) {
            assertThat(ToolPolicyDecision.decide(status, 2, READ, false, 2))
                    .as("연결 상태 %s", status)
                    .isEqualTo(new ToolPolicyDecision(ActionDecision.DENIED, ActionDenyReason.NOT_READY, null, null));
        }
    }

    @Test
    @DisplayName("schema 2 에서 선언이 없는 도구는 UNDECLARED 로 거절하고 위험도를 비운다")
    void undeclaredToolOfDeclaringSchemaIsDenied() {
        assertThat(ToolPolicyDecision.decide(ConnectionStatus.READY, 2, Optional.empty(), false, 2))
                .isEqualTo(new ToolPolicyDecision(ActionDecision.DENIED, ActionDenyReason.UNDECLARED, null, null));
    }

    @Test
    @DisplayName("schema 1 에서 선언이 없는 도구는 WRITE 와 required 로 읽어 승인 필요다")
    void undeclaredToolOfLegacySchemaNeedsApprovalAsWrite() {
        assertThat(ToolPolicyDecision.decide(ConnectionStatus.READY, 1, Optional.empty(), false, 2))
                .isEqualTo(new ToolPolicyDecision(
                        ActionDecision.NEEDS_APPROVAL, null, ToolRisk.WRITE, ToolApproval.REQUIRED));
    }

    @Test
    @DisplayName("위험도가 DESTRUCTIVE 나 FINANCIAL 이면 RISK_NOT_OPEN 으로 거절하고 위험도와 승인 방식을 남긴다")
    void destructiveAndFinancialRiskAreDenied() {
        for (ToolRisk risk : new ToolRisk[] {ToolRisk.DESTRUCTIVE, ToolRisk.FINANCIAL}) {
            Optional<ToolPolicy> declared = Optional.of(new ToolPolicy(risk, ToolApproval.ALWAYS, null));

            assertThat(ToolPolicyDecision.decide(ConnectionStatus.READY, 2, declared, true, 2))
                    .as("위험도 %s", risk)
                    .isEqualTo(new ToolPolicyDecision(
                            ActionDecision.DENIED, ActionDenyReason.RISK_NOT_OPEN, risk, ToolApproval.ALWAYS));
        }
    }

    @Test
    @DisplayName("인자 글이 16KB 이면 받고 한 바이트 넘으면 ARGS_TOO_LARGE 로 거절한다")
    void argsOverLimitAreDeniedAndArgsAtLimitAreNot() {
        int limit = 16 * 1024;

        assertThat(ToolPolicyDecision.decide(ConnectionStatus.READY, 2, READ, false, limit))
                .isEqualTo(new ToolPolicyDecision(ActionDecision.ALLOWED, null, ToolRisk.READ, ToolApproval.NONE));
        assertThat(ToolPolicyDecision.decide(ConnectionStatus.READY, 2, READ, false, limit + 1))
                .isEqualTo(new ToolPolicyDecision(
                        ActionDecision.DENIED, ActionDenyReason.ARGS_TOO_LARGE, ToolRisk.READ, ToolApproval.NONE));
    }

    @Test
    @DisplayName("승인 방식이 none 이면 허용이다")
    void approvalNoneIsAllowed() {
        assertThat(ToolPolicyDecision.decide(ConnectionStatus.READY, 2, READ, false, 2))
                .isEqualTo(new ToolPolicyDecision(ActionDecision.ALLOWED, null, ToolRisk.READ, ToolApproval.NONE));
    }

    @Test
    @DisplayName("승인 방식이 required 이고 상시 허락이 있으면 허용이다")
    void approvalRequiredWithGrantIsAllowed() {
        assertThat(ToolPolicyDecision.decide(ConnectionStatus.READY, 2, WRITE, true, 2))
                .isEqualTo(
                        new ToolPolicyDecision(ActionDecision.ALLOWED, null, ToolRisk.WRITE, ToolApproval.REQUIRED));
    }

    @Test
    @DisplayName("승인 방식이 required 이고 상시 허락이 없으면 승인 필요다")
    void approvalRequiredWithoutGrantNeedsApproval() {
        assertThat(ToolPolicyDecision.decide(ConnectionStatus.READY, 2, WRITE, false, 2))
                .isEqualTo(new ToolPolicyDecision(
                        ActionDecision.NEEDS_APPROVAL, null, ToolRisk.WRITE, ToolApproval.REQUIRED));
    }

    @Test
    @DisplayName("승인 방식이 always 이면 상시 허락이 있어도 승인 필요다")
    void approvalAlwaysNeedsApprovalEvenWithGrant() {
        Optional<ToolPolicy> declared = Optional.of(new ToolPolicy(ToolRisk.SENSITIVE, ToolApproval.ALWAYS, null));

        assertThat(ToolPolicyDecision.decide(ConnectionStatus.READY, 2, declared, true, 2))
                .isEqualTo(new ToolPolicyDecision(
                        ActionDecision.NEEDS_APPROVAL, null, ToolRisk.SENSITIVE, ToolApproval.ALWAYS));
    }
}
