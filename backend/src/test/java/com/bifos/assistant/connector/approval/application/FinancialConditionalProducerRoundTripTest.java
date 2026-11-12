package com.bifos.assistant.connector.approval.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import com.bifos.assistant.connector.action.domain.type.ActionStatus;
import com.bifos.assistant.connector.connection.application.dto.ConnectorPolicyAnswer;
import com.bifos.assistant.connector.financial.application.ConnectorExecutionSnapshot;
import com.bifos.assistant.connector.financial.application.ConnectorExecutionTicket;
import com.bifos.assistant.connector.financial.application.FinancialLimitExceeded;
import com.bifos.assistant.crypto.domain.TextCipher;
import com.bifos.assistant.hermes.application.dto.CallResult;
import com.bifos.assistant.hermes.application.dto.ConnectorCallError;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.testsupport.MysqlCase;
import com.bifos.assistant.testsupport.OverridableLiveProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 실제 producer 의 조건주문 request·approve 와 Spring claim HTTP, Python 처리기, 조건 SDK, loopback 목적지를 왕복한다.
 *
 * <p>목적지만 loopback 이고 승인 caller 는 이 검사가 producer 를 직접 부른다. 공개 승인 API 를 지나는 E2E 가 아니다.
 * 조건주문 도구는 테스트 설치 원본에만 선언하고 운영 assistant.json 은 금융 도구를 갖지 않는다.
 */
class FinancialConditionalProducerRoundTripTest extends FinancialConnectorTestBase {
    static final String CREATE = "create_conditional_order";
    static final String MODIFY = "modify_conditional_order";
    static final String CANCEL = "cancel_conditional_order";
    @Autowired
    TextCipher cipher;
    @Autowired
    ConnectorActionService publicApproval;
    @Autowired
    ConnectorExecutionTicket tickets;


    @Override
    String pluginScript() {
        return "conditional_plugin_http.py";
    }


    /** 실행 시각의 KST 날짜보다 30일 뒤다. 준비가 오늘부터 1년 안의 만료일만 받는다. */
    static String expireDate() {
        return LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(30).toString();
    }


    static String create(String symbol) {
        return "{\"symbol\":\"" + symbol + "\",\"type\":\"OCO\",\"quantity\":\"002\",\"orderType\":\"LIMIT\",\"expireDate\":\""
            + expireDate() + "\",\"first\":{\"orderSide\":\"SELL\",\"triggerPrice\":\"0110.00\",\"orderPrice\":\"110\"},"
            + "\"second\":{\"orderSide\":\"SELL\",\"triggerPrice\":\"90\",\"orderPrice\":\"90\"},\"confirmHighValueOrder\":false}";
    }


    static String modify(String id) {
        return "{\"conditionalOrderId\":\"" + id + "\",\"type\":\"OTO\",\"quantity\":\"1\",\"orderType\":\"LIMIT\",\"expireDate\":\""
            + expireDate() + "\",\"first\":{\"orderSide\":\"BUY\",\"triggerPrice\":\"95\",\"orderPrice\":\"95\"},"
            + "\"second\":{\"orderSide\":\"SELL\",\"triggerPrice\":\"130\",\"orderPrice\":\"130\"}}";
    }


    static String model(String tool) {
        return switch (tool) {
            case CREATE -> create("abc");
            case MODIFY -> modify("cond-1");
            default -> "{\"conditionalOrderId\":\"cond-1\"}";
        };
    }


    ConnectorPolicyAnswer request(String tool, String args) {
        return producer.request(profile, session, session, UUID.randomUUID().toString(), "mcp__tossinvest__" + tool, tool, args);
    }


    ConnectorExecutionSnapshot snapshot(ConnectorPolicyAnswer requested) {
        return ConnectorExecutionSnapshot.open(action(requested), contents.findById(action(requested).id()).orElseThrow(), cipher, digest);
    }


    long notifications() {
        return jdbc.queryForObject("select count(*) from notification where user_id = ?", Long.class, user.id());
    }


    @MysqlCase(MysqlCase.Reason.ROW_LOCK)
    @ParameterizedTest
    @DisplayName("실제 producer 가 저장한 조건 snapshot 을 승인이 그대로 한 번 보내고 replay 는 보내지 않는다")
    @ValueSource(strings = {CREATE, MODIFY, CANCEL})
    void roundTripsStoredConditionalSnapshotOnce(String tool) throws Exception {
        var requested = request(tool, model(tool));
        var stored = action(requested);
        assertThat(stored.status()).isEqualTo(ActionStatus.PENDING);
        assertThat(jdbc.queryForObject("select supersedes_unknown_action_id from connector_action_execution where action_id = ?",
            Long.class, stored.id())).isNull();
        assertThat(notifications()).isEqualTo(1);
        var row = contents.findById(stored.id()).orElseThrow();
        assertThat(row.contentKeyId()).isNotNull();
        assertThat(row.ticketId()).isNull();
        var snapshot = snapshot(requested);
        JsonNode args = JSON.readTree(snapshot.executionArgsJson());
        JsonNode summary = JSON.readTree(snapshot.summaryJson());
        assertThat(summary.path("orderKind").stringValue()).isEqualTo("CONDITIONAL");
        assertThat(summary.path("operation").stringValue()).isEqualTo(switch (tool) {
            case CREATE -> "CREATE";
            case MODIFY -> "MODIFY";
            default -> "CANCEL";
        });
        // 표시 값의 모든 leg 와 단위가 실제 open 결과와 같다. 취소는 원주문을 보인다.
        JsonNode shown = CANCEL.equals(tool) ? summary.path("original") : args;
        for (String field : List.of("type", "quantity", "orderType", "first", "second")) {
            assertThat(summary.get(field)).as(field).isEqualTo(shown.get(field));
        }
        assertThat(counters().path("prepare").intValue()).isEqualTo(1);
        assertThat(counters().path("financial").intValue()).isZero();

        var result = approval.approve(user, requested.actionId());
        assertThat(result.status()).as("목적지=%s, 오류=%s", counters(), action(requested).errorCode()).isEqualTo(ActionStatus.SUCCEEDED);
        // 승인은 저장 원문을 쓴다. 준비를 다시 부르지 않고 ticket 한 번, 실행 한 번이다.
        assertThat(sent.argsJson()).isEqualTo(snapshot.executionArgsJson());
        assertThat(counters().path("prepare").intValue()).isEqualTo(1);
        assertThat(counters().path("execute").intValue()).isEqualTo(1);
        assertThat(counters().path("financial").intValue()).isEqualTo(1);
        assertThat(counters().path("deletes").intValue()).isEqualTo(CANCEL.equals(tool) ? 1 : 0);
        var consumed = contents.findById(stored.id()).orElseThrow();
        assertThat(consumed.ticketId()).isNotNull();
        assertThat(consumed.consumedAt()).isEqualTo(now);
        assertThat(action(requested).resultText()).contains(CANCEL.equals(tool) ? "canceled" : "cond-new");
        JsonNode bodies = fixture("bodies", Map.of()).path("bodies");
        assertThat(bodies).hasSize(1);
        if (CREATE.equals(tool)) {
            // 준비가 만든 clientOrderId 를 저장 원문 그대로 보낸다. 승인은 UUID 를 새로 만들지 않는다.
            assertThat(JSON.readTree(bodies.get(0).stringValue()).path("clientOrderId")).isEqualTo(args.get("clientOrderId"));
        }
        assertThatThrownBy(() -> approval.approve(user, requested.actionId())).isInstanceOf(ApiException.class);
        assertThat(client.executeApproved(profile, "tossinvest", "mcp__tossinvest__" + tool, sent).ok()).isFalse();
        assertThat(counters().path("financial").intValue()).isEqualTo(1);
    }


    @ParameterizedTest
    @DisplayName("전송 뒤 결과를 모르면 재전송 없이 UNKNOWN 으로 남고 같은 의도의 새 준비를 막는다")
    @ValueSource(strings = {"429", "500", "timeout", "malformed", "400-unknown", "disconnect"})
    void uncertainResultsRemainUnknown(String mode) {
        configure(mode);
        var requested = request(CREATE, model(CREATE));
        assertThat(approval.approve(user, requested.actionId()).status()).isEqualTo(ActionStatus.UNKNOWN);
        assertThat(contents.findById(action(requested).id()).orElseThrow().consumedAt()).isNotNull();
        assertThat(counters().path("financial").intValue()).isEqualTo(1);
        assertThatThrownBy(() -> request(CREATE, model(CREATE))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> tickets.issue(requested.actionId())).isInstanceOf(ApiException.class);
        // 같은 의도 차단은 준비 뒤 사용자 잠금 아래에서 판정한다. 막힌 새 요청도 읽기 전용 준비는 한 번 지난다.
        assertThat(counters().path("prepare").intValue()).isEqualTo(2);
        assertThat(counters().path("financial").intValue()).isEqualTo(1);
    }


    @Test
    @DisplayName("공식 거절 응답은 재시도 없이 FAILED 로 남는다")
    void officialRejectionIsFailed() {
        configure("400");
        var requested = request(MODIFY, model(MODIFY));
        assertThat(approval.approve(user, requested.actionId()).status()).isEqualTo(ActionStatus.FAILED);
        assertThat(counters().path("financial").intValue()).isEqualTo(1);
    }


    @Test
    @DisplayName("준비 응답이 Spring 의 읽기 제한을 넘으면 줄과 snapshot, 알림을 남기지 않는다")
    void slowPrepareStoresNothing() throws Exception {
        configure("slow-prepare");
        assertThatThrownBy(() -> request(CREATE, model(CREATE))).isInstanceOf(ApiException.class);
        // 늦게 끝난 준비 결과도 받을 쪽이 없다. 대역이 답을 마칠 때까지 기다린 뒤 다시 센다.
        Thread.sleep(Duration.ofSeconds(1));
        assertThat(jdbc.queryForObject("select count(*) from connector_action where user_id = ?", Long.class, user.id())).isZero();
        assertThat(notifications()).isZero();
        assertThat(counters().path("financial").intValue()).isZero();
    }


    @MysqlCase(MysqlCase.Reason.ROW_LOCK)
    @Test
    @DisplayName("같은 의도는 clientOrderId 나 기대 원주문만 달라도 막고, 다른 종목과 다른 대상은 막지 않는다")
    void sameIntentUsesActualConflicts() {
        // 준비마다 clientOrderId 가 새로 생기지만 같은 의도다.
        request(CREATE, create("abc"));
        assertThatThrownBy(() -> request(CREATE, create("ABC"))).isInstanceOf(ApiException.class);
        assertThat(action(request(CREATE, create("XYZ"))).status()).isEqualTo(ActionStatus.PENDING);
        request(MODIFY, modify("cond-1"));
        // 기대 원주문만 바뀐 같은 정정이다.
        original(Map.of("status", "WATCHING"));
        assertThatThrownBy(() -> request(MODIFY, modify("cond-1"))).isInstanceOf(ApiException.class);
        original(Map.of("conditionalOrderId", "cond-2"));
        assertThat(action(request(MODIFY, modify("cond-2"))).status()).isEqualTo(ActionStatus.PENDING);
        assertThat(action(request(CANCEL, "{\"conditionalOrderId\":\"cond-2\"}")).status()).isEqualTo(ActionStatus.PENDING);
        assertThat(jdbc.queryForObject("select count(*) from connector_action where user_id = ? and status = 'PENDING'",
            Long.class, user.id())).isEqualTo(5);
        assertThat(counters().path("financial").intValue()).isZero();
    }


    @ParameterizedTest
    @DisplayName("claim 전에 바뀐 현재 원주문은 금융 요청을 보내지 않는다")
    @ValueSource(strings = {"status", "targetProfitRate", "triggeredOrderId", "expireDate"})
    void changedOriginalSendsNothing(String change) {
        var requested = request(MODIFY, model(MODIFY));
        switch (change) {
            case "status" -> original(Map.of("status", "COMPLETED"));
            case "targetProfitRate" -> secondLeg("targetProfitRate", "1.5");
            case "triggeredOrderId" -> secondLeg("triggeredOrderId", "ord-9");
            default -> original(Map.of("expireDate", expireDate()));
        }
        assertThat(approval.approve(user, requested.actionId()).status()).isIn(ActionStatus.FAILED, ActionStatus.UNKNOWN);
        assertThat(counters().path("financial").intValue()).isZero();
    }


    @ParameterizedTest
    @DisplayName("손상된 wire 와 바뀐 계좌 범위는 claim 과 금융 목적지에 닿지 못한다")
    @ValueSource(strings = {"extra", "number", "sha", "scope"})
    void malformedWireSendsNothing(String change) {
        var requested = request(CREATE, model(CREATE));
        doAnswer(invocation -> {
            sent = invocation.getArgument(3);
            ObjectNode args = (ObjectNode)JSON.readTree(sent.argsJson());
            switch (change) {
                case "extra" -> args.put("unknown", "value");
                case "number" -> args.put("quantity", 2);
                case "scope" -> args.put("account_seq", "8");
                default -> {
                }
            }
            var answer = post(pluginUrl + "/api/connectors/tossinvest/execute", Map.of("profile", profile,
                "hermes_tool", "mcp__tossinvest__" + CREATE, "args", args, "execution", Map.of("v", 1,
                    "protocol", "approval-claim-v2", "ticket", sent.ticket(), "argsJson", sent.argsJson(),
                    "argsSha256", "sha".equals(change) ? "0".repeat(64) : sent.argsSha256())),
                properties.dashboardToken());
            assertThat(answer.path("ok").asBoolean(false)).as("plugin 응답=%s", answer).isFalse();
            return CallResult.failure(ConnectorCallError.INVALID_INPUT);
        }).when(hermes).executeApproved(anyString(), anyString(), anyString(), any());
        assertThat(approval.approve(user, requested.actionId()).status()).isEqualTo(ActionStatus.FAILED);
        assertThat(contents.findById(action(requested).id()).orElseThrow().consumedAt()).isNull();
        assertThat(counters().path("financial").intValue()).isZero();
    }


    @ParameterizedTest
    @DisplayName("요청 뒤 바뀐 revision 과 만료한 ticket 은 금융 요청과 ticket 소비 없이 끝난다")
    @ValueSource(strings = {"revision", "expired"})
    void staleAuthorityOrTicketSendsNothing(String change) {
        var requested = request(CANCEL, model(CANCEL));
        doAnswer(invocation -> {
            sent = invocation.getArgument(3);
            if ("revision".equals(change)) {
                tx().executeWithoutResult(status -> bindings.findById(bindingId).orElseThrow().pending(now.plusSeconds(1)));
            } else {
                clock.set(now.plusSeconds(61));
            }
            return client.executeApproved(profile, "tossinvest", "mcp__tossinvest__" + CANCEL, sent);
        }).when(hermes).executeApproved(anyString(), anyString(), anyString(), any());
        assertThat(approval.approve(user, requested.actionId()).status()).isEqualTo(ActionStatus.FAILED);
        assertThat(contents.findById(action(requested).id()).orElseThrow().consumedAt()).isNull();
        assertThat(counters().path("financial").intValue()).isZero();
    }


    @Test
    @DisplayName("금융 성공 뒤 결과 저장이 실패해도 다시 승인하거나 정리해도 재전송하지 않는다")
    void resultSaveFailureDoesNotResend() {
        var requested = request(CREATE, model(CREATE));
        jdbc.execute("alter table connector_action add constraint conditional_result_failure check (user_id <> "
            + user.id() + " or status <> 'SUCCEEDED')");
        try {
            assertThatThrownBy(() -> approval.approve(user, requested.actionId())).isInstanceOf(RuntimeException.class);
            assertThat(action(requested).status()).isEqualTo(ActionStatus.EXECUTING);
            assertThatThrownBy(() -> approval.approve(user, requested.actionId())).isInstanceOf(RuntimeException.class);
        } finally {
            dropCheck("connector_action", "conditional_result_failure");
        }
        // 기동 정리는 소비한 claim 을 UNKNOWN 으로 남기고 다시 보내지 않는다.
        publicApproval.markInterrupted(now);
        assertThat(action(requested).status()).isEqualTo(ActionStatus.UNKNOWN);
        assertThat(counters().path("financial").intValue()).isEqualTo(1);
        assertThat(counters().path("execute").intValue()).isEqualTo(1);
    }


    @MysqlCase(MysqlCase.Reason.ROW_LOCK)
    @Test
    @DisplayName("동시 승인은 조건주문을 한 번만 보낸다")
    void concurrentApprovalSendsOnce() throws Exception {
        var requested = request(MODIFY, model(MODIFY));
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
    }


    @Test
    @DisplayName("조건주문 금액은 leg 마다 수량 × max(주문가, 감시가) 의 큰 값이고 시장가 조건주문은 닫힌다")
    void conditionalAmountUsesLargestLeg() {
        // 생성 대역은 2 × max(110, 110) = 220 USD 와 2 × 90 = 180 USD 의 두 leg 다.
        var base = gate.current();
        OverridableLiveProperties.during(gate, financial(true, owned("7"), "219"));
        assertThatThrownBy(() -> request(CREATE, model(CREATE))).isInstanceOf(FinancialLimitExceeded.class);
        OverridableLiveProperties.during(gate, financial(true, owned("7"), "220"));
        assertThat(action(request(CREATE, model(CREATE))).status()).isEqualTo(ActionStatus.PENDING);
        OverridableLiveProperties.during(gate, financial(true, owned("7"), base));
        String market = "{\"symbol\":\"abc\",\"type\":\"SINGLE\",\"quantity\":\"1\",\"orderType\":\"MARKET\",\"expireDate\":\""
            + expireDate() + "\",\"first\":{\"orderSide\":\"BUY\",\"triggerPrice\":\"95\"}}";
        assertThatThrownBy(() -> request(CREATE, market)).isInstanceOf(ApiException.class);
        assertThat(counters().path("financial").intValue()).isZero();
    }


    @Test
    @DisplayName("운영 assistant.json 은 조건주문 쓰기 도구를 선언하지 않는다")
    void productionManifestDeclaresNoConditionalWrite() throws Exception {
        JsonNode manifest = JSON.readTree(Files.readString(Path.of("../hermes/connectors/tossinvest/assistant.json")));
        for (String tool : List.of(CREATE, MODIFY, CANCEL)) {
            assertThat(manifest.path("tools").has(tool)).as(tool).isFalse();
            assertThat(manifest.path("execution_guard").path("operations").has(tool)).as(tool).isFalse();
        }
    }


    private void configure(String mode) {
        String sha = client.readCatalog().getFirst().executionGuardManifestSha256();
        control("configure", Map.of("profile", profile, "mode", mode, "guard", Map.of("v", 1,
            "protocol", "approval-claim-v2", "bindingId", bindingId.toString(), "connectionId", connectionId.toString(),
            "connectionUpdatedAt", connectionRevision.toString(), "manifestSha256", sha)));
    }


    /** 대역의 현재 원주문 상위 칸을 바꾼다. configure 가 되돌리므로 프로세스를 다시 띄우지 않는다. */
    private void original(Map<String, Object> changes) {
        ObjectNode current = (ObjectNode)fixture("current-original", Map.of()).path("original").deepCopy();
        changes.forEach((name, value) -> current.set(name, JSON.valueToTree(value)));
        fixture("original", current);
    }


    private void secondLeg(String name, String value) {
        ObjectNode current = (ObjectNode)fixture("current-original", Map.of()).path("original").deepCopy();
        ((ObjectNode)current.get("second")).put(name, value);
        fixture("original", current);
    }


    /** 대역 상태를 읽거나 configure 가 되돌리는 칸만 바꾸는 조작이다. 프로세스를 다시 띄우게 표시하지 않는다. */
    private JsonNode fixture(String operation, Object body) {
        try {
            return post(pluginUrl + "/fixture/" + operation, body, null);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
