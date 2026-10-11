package com.bifos.assistant.connector.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import com.bifos.assistant.connector.application.execution.ConnectorExecutionTicket;
import com.bifos.assistant.connector.domain.type.ActionStatus;
import com.bifos.assistant.hermes.dto.CallResult;
import com.bifos.assistant.hermes.dto.ConnectorCallError;
import com.bifos.assistant.shared.error.ApiException;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;

/** 실제 DB 권한과 Spring claim HTTP, source Python, SDK bundle, mock 목적지를 왕복한다. */
class FinancialConnectorProducerRoundTripTest extends FinancialConnectorProducerTest {
    @Autowired
    ConnectorExecutionTicket tickets;
    @Autowired
    ConnectorActionService publicApproval;
    @Autowired
    ConnectorPolicyService publicPolicy;


    @Test
    @DisplayName("실제 claim은 금융 POST를 한 번 보내고 재호출은 보내지 않는다")
    void actualClaimSendsOneFinancialPostAndReplaySendsNone() throws Exception {
        var requested = request();
        var result = approval.approve(user, requested.actionId());
        assertThat(result.status()).as("목적지 관측=%s, ticket 전달=%s, 오류=%s", counters(), sent != null, action(requested).errorCode()).isEqualTo(ActionStatus.SUCCEEDED);
        assertThat(action(requested).resultText()).contains("new-official-id");
        var row = contents.findById(action(requested).id()).orElseThrow();
        assertThat(row.ticketId()).isNotNull();
        assertThat(row.consumedAt()).isEqualTo(now);
        assertThat(row.ticketExpiresAt()).isEqualTo(now.plusSeconds(60));
        assertThat(counters().path("financial").intValue()).isEqualTo(1);
        assertThat(counters().path("auth").intValue()).isEqualTo(2);
        assertThat(counters().path("execute").intValue()).isEqualTo(1);
        assertThat(counters().path("deletes").intValue()).isZero();
        assertThatThrownBy(() -> approval.approve(user, requested.actionId())).isInstanceOf(ApiException.class);
        var replay = client.executeApproved(profile, "tossinvest", "mcp__tossinvest__create_order", sent);
        assertThat(replay.ok()).isFalse();
        assertThat(counters().path("financial").intValue()).isEqualTo(1);
        assertThatThrownBy(() -> tickets.issue(requested.actionId())).isInstanceOf(ApiException.class);
    }


    @ParameterizedTest
    @DisplayName("불확실한 결과는 재시도 없이 UNKNOWN으로 남는다")
    @ValueSource(strings = {"429", "500", "timeout", "malformed", "400-unknown"})
    void uncertainResultsRemainUnknownWithoutAutomaticRetry(String mode) {
        configureMode(mode);
        var requested = request();
        var result = approval.approve(user, requested.actionId());
        assertThat(result.status()).isEqualTo(ActionStatus.UNKNOWN);
        assertThat(contents.findById(action(requested).id()).orElseThrow().consumedAt()).isNotNull();
        assertThat(counters().path("financial").intValue()).isEqualTo(1);
        assertThat(counters().path("execute").intValue()).isEqualTo(1);
        assertThat(counters().path("deletes").intValue()).isZero();
        assertThatThrownBy(this::request).isInstanceOf(ApiException.class);
        assertThat(counters().path("financial").intValue()).isEqualTo(1);
    }


    @Test
    @DisplayName("지원하는 거절 응답은 재시도 없이 FAILED로 남는다")
    void officialRejectionIsFailedWithoutRetry() {
        configureMode("400");
        var requested = request();
        assertThat(approval.approve(user, requested.actionId()).status()).isEqualTo(ActionStatus.FAILED);
        assertThat(counters().path("financial").intValue()).isEqualTo(1);
        assertThat(counters().path("execute").intValue()).isEqualTo(1);
    }


    @Test
    @DisplayName("바뀐 선언은 전송 전에 거부된다")
    void changedManifestRejectsBeforeTransmission() {
        var requested = request();
        control("manifest-change", Map.of());
        assertThatThrownBy(() -> approval.approve(user, requested.actionId())).isInstanceOf(RuntimeException.class);
        assertThat(action(requested).status()).isEqualTo(ActionStatus.PENDING);
        assertThat(counters().path("financial").intValue()).isZero();
        assertThat(counters().path("execute").intValue()).isZero();
    }


    @Test
    @DisplayName("공개 정책과 승인 경로의 금융 실행 차단을 유지한다")
    void publicHookAndPublicApprovalKeepFinancialClosed() {
        var blocked = publicPolicy.decide(profile, session, session, "public", "mcp__tossinvest__create_order", "create_order", ARGS);
        assertThat(blocked.allowed()).isFalse();
        assertThat(blocked.actionId()).isNull();
        var requested = request();
        assertThat(publicApproval.approve(user, requested.actionId(), null).status()).isEqualTo(ActionStatus.REJECTED);
        assertThat(counters().path("execute").intValue()).isZero();
        assertThat(counters().path("financial").intValue()).isZero();
    }


    @Test
    @DisplayName("동시 승인은 금융 요청을 한 번만 실행한다")
    void concurrentApprovalExecutesOnlyOnce() throws Exception {
        var requested = request();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Callable<Boolean> call = () -> {
                try {
                    return approval.approve(user, requested.actionId()).status() == ActionStatus.SUCCEEDED;
                } catch (ApiException ignored) {
                    return false;
                }
            };
            var first = executor.submit(call);
            var second = executor.submit(call);
            assertThat(first.get(20, TimeUnit.SECONDS) ^ second.get(20, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(counters().path("financial").intValue()).isEqualTo(1);
        assertThat(counters().path("execute").intValue()).isEqualTo(1);
    }


    @Test
    @DisplayName("claim 중 바뀐 바인딩은 금융 전송과 ticket 소비 전에 거부된다")
    void changedRevisionDuringClaimIsRejectedWithZeroFinancialRequests() {
        var requested = request();
        doAnswer(invocation -> {
            sent = invocation.getArgument(3);
            tx().executeWithoutResult(status -> bindings.findById(bindingId).orElseThrow().pending(now.plusSeconds(1)));
            return client.executeApproved(profile, "tossinvest", "mcp__tossinvest__create_order", sent);
        }).when(hermes).executeApproved(anyString(), anyString(), anyString(), any());
        assertThat(approval.approve(user, requested.actionId()).status()).isEqualTo(ActionStatus.FAILED);
        assertThat(counters().path("financial").intValue()).isZero();
        assertThat(contents.findById(action(requested).id()).orElseThrow().consumedAt()).isNull();
    }


    @ParameterizedTest
    @DisplayName("손상된 wire는 claim과 금융 목적지에 도달하지 못한다")
    @ValueSource(strings = {"missing", "extra", "number", "sha", "scope"})
    void malformedWireCannotReachClaimOrFinancialDestination(String change) {
        var requested = request();
        doAnswer(invocation -> {
            sent = invocation.getArgument(3);
            ObjectNode args = (ObjectNode)JSON.readTree(sent.argsJson());
            switch (change) {
                case "missing" -> args.remove("quantity");
                case "extra" -> args.put("unknown", "value");
                case "number" -> args.put("quantity", 2);
                case "scope" -> args.put("account_seq", "8");
                default -> {
                }
            }
            post(pluginUrl + "/api/connectors/tossinvest/execute", Map.of("profile", profile,
                "hermes_tool", "mcp__tossinvest__create_order", "args", args, "execution", Map.of("v", 1,
                    "protocol", "approval-claim-v1", "ticket", sent.ticket(), "argsJson", sent.argsJson(),
                    "argsSha256", "sha".equals(change) ? "0".repeat(64) : sent.argsSha256())),
                properties.dashboardToken());
            return CallResult.failure(ConnectorCallError.INVALID_INPUT);
        }).when(hermes).executeApproved(anyString(), anyString(), anyString(), any());
        assertThat(approval.approve(user, requested.actionId()).status()).isEqualTo(ActionStatus.FAILED);
        assertThat(contents.findById(action(requested).id()).orElseThrow().consumedAt()).isNull();
        assertThat(counters().path("financial").intValue()).isZero();
        assertThat(counters().path("deletes").intValue()).isZero();
    }


    @Test
    @DisplayName("만료된 claim은 금융 요청을 보내지 않는다")
    void claimExpirationHasZeroFinancialRequests() {
        var requested = request();
        doAnswer(invocation -> {
            sent = invocation.getArgument(3);
            clock.set(now.plusSeconds(61));
            return client.executeApproved(profile, "tossinvest", "mcp__tossinvest__create_order", sent);
        }).when(hermes).executeApproved(anyString(), anyString(), anyString(), any());
        assertThat(approval.approve(user, requested.actionId()).status()).isEqualTo(ActionStatus.FAILED);
        assertThat(contents.findById(action(requested).id()).orElseThrow().consumedAt()).isNull();
        assertThat(counters().path("financial").intValue()).isZero();
    }


    @Test
    @DisplayName("결과 저장 실패 뒤에도 이미 실행한 금융 요청은 재전송하지 않는다")
    void resultSaveFailureDoesNotRepeatCommittedClaimOrFinancialRequest() {
        var requested = request();
        jdbc.execute("alter table connector_action add constraint producer_result_failure check (user_id <> "
            + user.id() + " or status <> 'SUCCEEDED')");
        try {
            assertThatThrownBy(() -> approval.approve(user, requested.actionId())).isInstanceOf(RuntimeException.class);
            assertThat(action(requested).status()).isEqualTo(ActionStatus.EXECUTING);
            assertThat(contents.findById(action(requested).id()).orElseThrow().consumedAt()).isNotNull();
            assertThatThrownBy(() -> approval.approve(user, requested.actionId())).isInstanceOf(RuntimeException.class);
            assertThat(counters().path("financial").intValue()).isEqualTo(1);
            assertThat(counters().path("execute").intValue()).isEqualTo(1);
        } finally {
            dropCheck("connector_action", "producer_result_failure");
        }
    }


    private void configureMode(String mode) {
        String sha = client.readCatalog().getFirst().executionGuardManifestSha256();
        control("configure", Map.of("profile", profile, "mode", mode, "guard", Map.of("v", 1,
            "protocol", "approval-claim-v1", "bindingId", bindingId.toString(), "connectionId", connectionId.toString(),
            "connectionUpdatedAt", connectionRevision.toString(), "manifestSha256", sha)));
    }
}
