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
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.user.domain.AppUser;
import java.util.UUID;
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


    @Test
    @DisplayName("다른 사용자의 승인은 금융 줄을 찾지 못하고 실행하지 않는다")
    void otherUserCannotApproveFinancialAction() {
        var requested = request();
        var other = tx().execute(status -> users.saveAndFlush(AppUser.of(UUID.randomUUID() + "@example.test", "다른 사람", 1L,
            UserRole.MEMBER, now)));
        var stranger = new CurrentUser(other.id(), other.email(), "다른 사람", 1L, UserRole.MEMBER);
        assertThatThrownBy(() -> approval.approve(stranger, requested.actionId())).isInstanceOf(ApiException.class);
        assertRejectedBeforeTransmission(requested.actionId());
    }


    @ParameterizedTest
    @DisplayName("요청 뒤 바뀐 권한 맥락은 승인 전에 거부된다")
    @ValueSource(strings = {"desired-disabled", "disconnected", "profile", "owner"})
    void changedAuthorityAfterRequestIsRejectedBeforeTransmission(String change) {
        var requested = request();
        switch (change) {
            case "desired-disabled" -> jdbc.update("update agent_connector_binding set desired_enabled = false where id = ?", bindingId);
            case "disconnected" -> tx().executeWithoutResult(status -> connections.findById(connectionId).orElseThrow().disconnected(now.plusSeconds(1)));
            case "profile" -> jdbc.update("update agent set hermes_profile = ? where id = (select agent_id from agent_connector_binding where id = ?)",
                profile + "-moved", bindingId);
            default -> {
                var other = tx().execute(status -> users.saveAndFlush(AppUser.of(UUID.randomUUID() + "@example.test", "새 주인", 1L,
                    UserRole.MEMBER, now)));
                jdbc.update("update agent set owner_user_id = ? where id = (select agent_id from agent_connector_binding where id = ?)",
                    other.id(), bindingId);
            }
        }
        // 옮긴 profile은 Hermes 상태 조회에서, 나머지는 잠금 아래 재검증에서 거부된다.
        assertThatThrownBy(() -> approval.approve(user, requested.actionId())).isInstanceOf(RuntimeException.class);
        assertRejectedBeforeTransmission(requested.actionId());
    }


    @Test
    @DisplayName("snapshot 없는 금융 줄은 승인해도 실행하지 않는다")
    void financialActionWithoutSnapshotIsNotExecuted() {
        var requested = request();
        jdbc.update("delete from connector_action_execution where action_id = ?", action(requested).id());
        assertThatThrownBy(() -> approval.approve(user, requested.actionId())).isInstanceOf(ApiException.class);
        assertRejectedBeforeTransmission(requested.actionId());
    }


    @Test
    @DisplayName("암호화하지 않은 snapshot은 승인해도 실행하지 않는다")
    void plaintextSnapshotIsNotExecuted() {
        var requested = request();
        jdbc.update("update connector_action_execution set content_key_id = null where action_id = ?", action(requested).id());
        assertThatThrownBy(() -> approval.approve(user, requested.actionId())).isInstanceOf(ApiException.class);
        assertRejectedBeforeTransmission(requested.actionId());
    }


    @Test
    @DisplayName("같은 의도의 동시 준비는 PENDING 한 줄만 남긴다")
    void concurrentSameIntentRequestsLeaveOnePending() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Callable<Boolean> call = () -> {
                try {
                    request();
                    return true;
                } catch (ApiException ignored) {
                    return false;
                }
            };
            var first = executor.submit(call);
            var second = executor.submit(call);
            assertThat(first.get(20, TimeUnit.SECONDS) ^ second.get(20, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(jdbc.queryForObject("select count(*) from connector_action where user_id = ? and status = 'PENDING'",
            Long.class, user.id())).isEqualTo(1);
        assertThat(counters().path("financial").intValue()).isZero();
    }


    @Test
    @DisplayName("claim 직전에 생긴 같은 의도의 UNKNOWN은 ticket 소비와 금융 전송을 막는다")
    void claimRejectsSameIntentUnknownCreatedAfterApproval() {
        var earlier = request();
        // REJECTED 같은 의도는 새 준비를 막지 않는다.
        Long earlierId = action(earlier).id();
        jdbc.update("update connector_action set status = 'REJECTED' where id = ?", earlierId);
        var requested = request();
        doAnswer(invocation -> {
            sent = invocation.getArgument(3);
            jdbc.update("update connector_action set status = 'UNKNOWN' where id = ?", earlierId);
            return client.executeApproved(profile, "tossinvest", "mcp__tossinvest__create_order", sent);
        }).when(hermes).executeApproved(anyString(), anyString(), anyString(), any());
        assertThat(approval.approve(user, requested.actionId()).status()).isEqualTo(ActionStatus.FAILED);
        assertThat(contents.findById(action(requested).id()).orElseThrow().consumedAt()).isNull();
        assertThat(counters().path("financial").intValue()).isZero();
        assertThat(counters().path("deletes").intValue()).isZero();
    }


    private void assertRejectedBeforeTransmission(UUID actionId) {
        var action = actions.findByPublicId(actionId).orElseThrow();
        assertThat(action.status()).isEqualTo(ActionStatus.PENDING);
        contents.findById(action.id()).ifPresent(row -> assertThat(row.ticketId()).isNull());
        assertThat(counters().path("execute").intValue()).isZero();
        assertThat(counters().path("financial").intValue()).isZero();
        assertThat(counters().path("deletes").intValue()).isZero();
    }


    private void configureMode(String mode) {
        String sha = client.readCatalog().getFirst().executionGuardManifestSha256();
        control("configure", Map.of("profile", profile, "mode", mode, "guard", Map.of("v", 1,
            "protocol", "approval-claim-v1", "bindingId", bindingId.toString(), "connectionId", connectionId.toString(),
            "connectionUpdatedAt", connectionRevision.toString(), "manifestSha256", sha)));
    }
}
