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

/** 판정 순서는 {@code docs/backend/connector-tool-policy.md} 의 「도구 호출 판정」 표다. */
class ToolPolicyDecisionTest {
    private static final Optional<ToolPolicy> READ =
            Optional.of(new ToolPolicy(ToolRisk.READ, ToolApproval.NONE, null, false));
    private static final Optional<ToolPolicy> WRITE =
            Optional.of(new ToolPolicy(ToolRisk.WRITE, ToolApproval.REQUIRED, null, true));

    @Test
    @DisplayName("manifest 를 읽지 못한 판정은 POLICY_UNAVAILABLE 거절이고 위험도와 승인 방식이 비어 있다")
    void policyUnavailableIsDeniedWithoutRiskAndApproval() {
        assertThat(ToolPolicyDecision.policyUnavailable())
                .isEqualTo(
                        new ToolPolicyDecision(ActionDecision.DENIED, ActionDenyReason.POLICY_UNAVAILABLE, null, null));
    }

    @Test
    @DisplayName("연결이 READY 가 아니면 읽기 도구여도 NOT_READY 로 거절하고 위험도를 비운다")
    void connectionThatIsNotReadyIsDeniedBeforeAnythingElse() {
        for (ConnectionStatus status :
                new ConnectionStatus[] {ConnectionStatus.PENDING, ConnectionStatus.DISCONNECTED}) {
            assertThat(ToolPolicyDecision.decide(status, true, 2, READ, false, 2, false))
                    .as("연결 상태 %s", status)
                    .isEqualTo(new ToolPolicyDecision(ActionDecision.DENIED, ActionDenyReason.NOT_READY, null, null));
        }
    }

    @Test
    @DisplayName("schema 2 에서 선언이 없는 도구는 UNDECLARED 로 거절하고 위험도를 비운다")
    void undeclaredToolOfDeclaringSchemaIsDenied() {
        assertThat(ToolPolicyDecision.decide(ConnectionStatus.READY, true, 2, Optional.empty(), false, 2, false))
                .isEqualTo(new ToolPolicyDecision(ActionDecision.DENIED, ActionDenyReason.UNDECLARED, null, null));
    }

    @Test
    @DisplayName("등록 이름이 그 커넥터의 MCP 서버 것이 아니면 schema 1 이어도, 선언이 있어도 UNDECLARED 로 거절한다")
    void toolOfAnotherServerIsDeniedRegardlessOfSchema() {
        ToolPolicyDecision denied =
                new ToolPolicyDecision(ActionDecision.DENIED, ActionDenyReason.UNDECLARED, null, null);

        assertThat(ToolPolicyDecision.decide(ConnectionStatus.READY, false, 1, Optional.empty(), false, 2, false))
                .isEqualTo(denied);
        assertThat(ToolPolicyDecision.decide(ConnectionStatus.READY, false, 2, READ, false, 2, false))
                .isEqualTo(denied);
    }

    @Test
    @DisplayName("schema 1 에서 선언이 없는 도구는 WRITE 와 required 로 읽어 승인 필요다")
    void undeclaredToolOfLegacySchemaNeedsApprovalAsWrite() {
        assertThat(ToolPolicyDecision.decide(ConnectionStatus.READY, true, 1, Optional.empty(), false, 2, false))
                .isEqualTo(new ToolPolicyDecision(
                        ActionDecision.NEEDS_APPROVAL, null, ToolRisk.WRITE, ToolApproval.REQUIRED));
    }

    @Test
    @DisplayName("위험도가 DESTRUCTIVE 나 FINANCIAL 이면 RISK_NOT_OPEN 으로 거절하고 위험도와 승인 방식을 남긴다")
    void destructiveAndFinancialRiskAreDenied() {
        for (ToolRisk risk : new ToolRisk[] {ToolRisk.DESTRUCTIVE, ToolRisk.FINANCIAL}) {
            Optional<ToolPolicy> declared = Optional.of(new ToolPolicy(risk, ToolApproval.ALWAYS, null, false));

            assertThat(ToolPolicyDecision.decide(ConnectionStatus.READY, true, 2, declared, true, 2, false))
                    .as("위험도 %s", risk)
                    .isEqualTo(new ToolPolicyDecision(
                            ActionDecision.DENIED, ActionDenyReason.RISK_NOT_OPEN, risk, ToolApproval.ALWAYS));
        }
    }

    @Test
    @DisplayName("인자 글이 16KB 이면 받고 한 바이트 넘으면 ARGS_TOO_LARGE 로 거절한다")
    void argsOverLimitAreDeniedAndArgsAtLimitAreNot() {
        int limit = 16 * 1024;

        assertThat(ToolPolicyDecision.decide(ConnectionStatus.READY, true, 2, READ, false, limit, false))
                .isEqualTo(new ToolPolicyDecision(ActionDecision.ALLOWED, null, ToolRisk.READ, ToolApproval.NONE));
        assertThat(ToolPolicyDecision.decide(ConnectionStatus.READY, true, 2, READ, false, limit + 1, false))
                .isEqualTo(new ToolPolicyDecision(
                        ActionDecision.DENIED, ActionDenyReason.ARGS_TOO_LARGE, ToolRisk.READ, ToolApproval.NONE));
    }

    @Test
    @DisplayName("승인 방식이 none 이면 허용이다")
    void approvalNoneIsAllowed() {
        assertThat(ToolPolicyDecision.decide(ConnectionStatus.READY, true, 2, READ, false, 2, false))
                .isEqualTo(new ToolPolicyDecision(ActionDecision.ALLOWED, null, ToolRisk.READ, ToolApproval.NONE));
    }

    @Test
    @DisplayName("승인 방식이 required 이고 상시 허락이 있으면 허용이다")
    void approvalRequiredWithGrantIsAllowed() {
        assertThat(ToolPolicyDecision.decide(ConnectionStatus.READY, true, 2, WRITE, true, 2, false))
                .isEqualTo(new ToolPolicyDecision(ActionDecision.ALLOWED, null, ToolRisk.WRITE, ToolApproval.REQUIRED));
    }

    @Test
    @DisplayName("선언이 상시 허락을 닫은 required 도구는 상시 허락이 있어도 승인 필요다")
    void approvalRequiredWithClosedGrantNeedsApprovalEvenWithGrant() {
        Optional<ToolPolicy> closed = Optional.of(new ToolPolicy(ToolRisk.WRITE, ToolApproval.REQUIRED, null, false));

        assertThat(ToolPolicyDecision.decide(ConnectionStatus.READY, true, 2, closed, true, 2, false))
                .isEqualTo(new ToolPolicyDecision(
                        ActionDecision.NEEDS_APPROVAL, null, ToolRisk.WRITE, ToolApproval.REQUIRED));
    }

    @Test
    @DisplayName("schema 1 에서 선언이 없는 도구는 상시 허락이 있으면 허용이다")
    void undeclaredToolOfLegacySchemaIsAllowedWithGrant() {
        assertThat(ToolPolicyDecision.decide(ConnectionStatus.READY, true, 1, Optional.empty(), true, 2, false))
                .isEqualTo(new ToolPolicyDecision(ActionDecision.ALLOWED, null, ToolRisk.WRITE, ToolApproval.REQUIRED));
    }

    @Test
    @DisplayName("승인 방식이 required 이고 상시 허락이 없으면 승인 필요다")
    void approvalRequiredWithoutGrantNeedsApproval() {
        assertThat(ToolPolicyDecision.decide(ConnectionStatus.READY, true, 2, WRITE, false, 2, false))
                .isEqualTo(new ToolPolicyDecision(
                        ActionDecision.NEEDS_APPROVAL, null, ToolRisk.WRITE, ToolApproval.REQUIRED));
    }

    @Test
    @DisplayName("승인 방식이 always 이면 상시 허락이 있어도 승인 필요다")
    void approvalAlwaysNeedsApprovalEvenWithGrant() {
        Optional<ToolPolicy> declared =
                Optional.of(new ToolPolicy(ToolRisk.SENSITIVE, ToolApproval.ALWAYS, null, false));

        assertThat(ToolPolicyDecision.decide(ConnectionStatus.READY, true, 2, declared, true, 2, false))
                .isEqualTo(new ToolPolicyDecision(
                        ActionDecision.NEEDS_APPROVAL, null, ToolRisk.SENSITIVE, ToolApproval.ALWAYS));
    }

    @Test
    @DisplayName("살펴보기 트리에서는 READ 이고 none 인 도구만 허용한다")
    void readOnlyRunAllowsOnlyReadToolWithoutApproval() {
        assertThat(ToolPolicyDecision.decide(ConnectionStatus.READY, true, 2, READ, false, 2, true))
                .isEqualTo(new ToolPolicyDecision(ActionDecision.ALLOWED, null, ToolRisk.READ, ToolApproval.NONE));
    }

    @Test
    @DisplayName("살펴보기 트리에서 WRITE 와 required 도구는 상시 허락이 있어도 READ_ONLY_RUN 으로 거절한다")
    void readOnlyRunDeniesWriteToolEvenWithGrant() {
        for (boolean granted : new boolean[] {false, true}) {
            assertThat(ToolPolicyDecision.decide(ConnectionStatus.READY, true, 2, WRITE, granted, 2, true))
                    .as("상시 허락 %s", granted)
                    .isEqualTo(new ToolPolicyDecision(
                            ActionDecision.DENIED, ActionDenyReason.READ_ONLY_RUN, ToolRisk.WRITE, ToolApproval.REQUIRED));
        }
    }

    @Test
    @DisplayName("살펴보기 트리에서 READ 라도 required 나 always 로 선언한 도구는 상시 허락이 있어도 READ_ONLY_RUN 이다")
    void readOnlyRunDeniesReadToolThatDeclaresApproval() {
        for (ToolApproval approval : new ToolApproval[] {ToolApproval.REQUIRED, ToolApproval.ALWAYS}) {
            Optional<ToolPolicy> declared = Optional.of(new ToolPolicy(ToolRisk.READ, approval, null, true));

            assertThat(ToolPolicyDecision.decide(ConnectionStatus.READY, true, 2, declared, true, 2, true))
                    .as("승인 방식 %s", approval)
                    .isEqualTo(new ToolPolicyDecision(
                            ActionDecision.DENIED, ActionDenyReason.READ_ONLY_RUN, ToolRisk.READ, approval));
        }
    }

    @Test
    @DisplayName("살펴보기 트리에서 schema 1 의 선언 없는 도구는 WRITE 로 읽어 READ_ONLY_RUN 이다")
    void readOnlyRunDeniesUndeclaredToolOfLegacySchema() {
        assertThat(ToolPolicyDecision.decide(ConnectionStatus.READY, true, 1, Optional.empty(), true, 2, true))
                .isEqualTo(new ToolPolicyDecision(
                        ActionDecision.DENIED, ActionDenyReason.READ_ONLY_RUN, ToolRisk.WRITE, ToolApproval.REQUIRED));
    }

    @Test
    @DisplayName("살펴보기 트리에서도 앞선 거절이 먼저이고 인자 크기 판정은 뒤다")
    void readOnlyRunKeepsOrderAroundOtherDenials() {
        Optional<ToolPolicy> destructive =
                Optional.of(new ToolPolicy(ToolRisk.DESTRUCTIVE, ToolApproval.ALWAYS, null, false));

        assertThat(ToolPolicyDecision.decide(ConnectionStatus.READY, true, 2, destructive, false, 2, true)
                        .denyReason())
                .isEqualTo(ActionDenyReason.RISK_NOT_OPEN);
        assertThat(ToolPolicyDecision.decide(ConnectionStatus.READY, true, 2, WRITE, false, 16 * 1024 + 1, true)
                        .denyReason())
                .isEqualTo(ActionDenyReason.READ_ONLY_RUN);
        assertThat(ToolPolicyDecision.decide(ConnectionStatus.READY, true, 2, READ, false, 16 * 1024 + 1, true)
                        .denyReason())
                .isEqualTo(ActionDenyReason.ARGS_TOO_LARGE);
    }

    @Test
    @DisplayName("살펴보기 트리가 아니면 READ 와 required 도구는 지금처럼 상시 허락으로 허용이다")
    void notReadOnlyRunKeepsGrantForReadToolThatDeclaresApproval() {
        Optional<ToolPolicy> declared = Optional.of(new ToolPolicy(ToolRisk.READ, ToolApproval.REQUIRED, null, true));

        assertThat(ToolPolicyDecision.decide(ConnectionStatus.READY, true, 2, declared, true, 2, false))
                .isEqualTo(new ToolPolicyDecision(ActionDecision.ALLOWED, null, ToolRisk.READ, ToolApproval.REQUIRED));
        assertThat(ToolPolicyDecision.decide(ConnectionStatus.READY, true, 2, declared, false, 2, false))
                .isEqualTo(new ToolPolicyDecision(
                        ActionDecision.NEEDS_APPROVAL, null, ToolRisk.READ, ToolApproval.REQUIRED));
    }
}
