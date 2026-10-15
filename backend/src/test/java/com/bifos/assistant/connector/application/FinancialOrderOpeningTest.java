package com.bifos.assistant.connector.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.connector.application.model.ConnectorPolicyAnswer;
import com.bifos.assistant.connector.application.model.FinancialActionSummary;
import com.bifos.assistant.connector.domain.ConnectionFields;
import com.bifos.assistant.connector.domain.ConnectorAction;
import com.bifos.assistant.connector.domain.ConnectorActionKey;
import com.bifos.assistant.connector.domain.ConnectorBinding;
import com.bifos.assistant.connector.domain.ConnectorConnection;
import com.bifos.assistant.connector.domain.ToolPolicyDecision;
import com.bifos.assistant.connector.domain.type.ActionDecision;
import com.bifos.assistant.connector.domain.type.ActionDenyReason;
import com.bifos.assistant.connector.domain.type.ActionStatus;
import com.bifos.assistant.connector.domain.type.GrantPeriod;
import com.bifos.assistant.connector.domain.type.ToolApproval;
import com.bifos.assistant.connector.domain.type.ToolRisk;
import com.bifos.assistant.connector.infra.ConnectorActionBodies;
import com.bifos.assistant.crypto.domain.TextCipher;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.shared.util.Sha256;
import com.bifos.assistant.task.domain.Task;
import com.bifos.assistant.task.domain.TaskRun;
import com.bifos.assistant.task.domain.TaskTrigger;
import com.bifos.assistant.task.domain.type.ConversationMode;
import com.bifos.assistant.task.domain.type.MissedPolicy;
import com.bifos.assistant.task.domain.type.NotifyPolicy;
import com.bifos.assistant.task.infra.TaskRepository;
import com.bifos.assistant.task.infra.TaskRunRepository;
import com.bifos.assistant.task.infra.TaskTriggerRepository;
import com.bifos.assistant.testsupport.OverridableLiveProperties;
import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

/** 공개 진입점(정책 판정과 승인 API)이 금융 준비와 금융 승인으로 이어지는지 실제 plugin 왕복으로 본다. */
class FinancialOrderOpeningTest extends FinancialConnectorTestBase {
    static final String PREPARE_TOOL = "mcp__tossinvest__prepare_trade";
    static final String EXECUTION_TOOL = "mcp__tossinvest__create_order";
    static final String ROTATED_DIGEST_KEY = "src/test/resources/financial/test-digest-rotated.key";
    @Autowired
    ConnectorPolicyService publicPolicy;
    @Autowired
    ConnectorActionService publicApproval;
    @Autowired
    ConnectorFinancialSummaries summaries;
    @Autowired
    ConnectorActionBodies bodies;
    @Autowired
    TextCipher cipher;
    @Autowired
    TaskRepository tasks;
    @Autowired
    TaskTriggerRepository triggers;
    @Autowired
    TaskRunRepository taskRuns;


    @ParameterizedTest
    @ValueSource(strings = {"no-key", "no-accounts", "zero-limit", "legacy-protocol", "previous-key"})
    @DisplayName("관문이 닫힌 조건에서는 준비 도구 호출과 공개 승인이 모두 금융 POST 와 claim 을 보내지 않는다")
    void closedGateSendsNothingThroughPublicEntries(String condition) {
        var pending = request();
        var open = gate.current();
        switch (condition) {
            case "no-key" -> OverridableLiveProperties.during(gate, with(open, open.allowedAccounts(), open.maxOrderAmountUsd(), ""));
            case "no-accounts" -> OverridableLiveProperties.during(gate, with(open, List.of(), open.maxOrderAmountUsd(), open.digestKeyFile()));
            // 대역 종목은 USD 다.
            case "zero-limit" -> OverridableLiveProperties.during(gate, with(open, open.allowedAccounts(), BigDecimal.ZERO, open.digestKeyFile()));
            case "legacy-protocol" -> {
                var legacy = producer.request(profile, session, session, UUID.randomUUID().toString(), EXECUTION_TOOL,
                    "create_order", ARGS.replace("002", "003"));
                jdbc.update("update connector_action_execution set protocol = 'approval-claim-v1' where action_id = ?", action(legacy).id());
            }
            // 재기동하며 key 를 바꾼 것과 같다. 앞서 만든 PENDING 줄이 이전 key 의 열린 줄이 된다.
            default -> OverridableLiveProperties.during(gate, with(open, open.allowedAccounts(), open.maxOrderAmountUsd(), ROTATED_DIGEST_KEY));
        }
        long rows = rows();
        var answer = prepare(UUID.randomUUID().toString(), trade("create_order", ARGS.replace("002", "004")));
        assertThat(answer.allowed()).isFalse();
        assertThat(answer.actionId()).isNull();
        assertThat(rows()).isEqualTo(rows);
        assertThatThrownBy(() -> publicApproval.approve(user, pending.actionId(), null, false)).isInstanceOf(ApiException.class);
        assertThat(action(pending).status()).isEqualTo(ActionStatus.PENDING);
        assertThat(counters().path("financial").intValue()).isZero();
        assertThat(counters().path("execute").intValue()).isZero();
        assertThat(claims()).isZero();
    }


    @Test
    @DisplayName("준비 도구 호출은 실행 도구 이름의 PENDING 금융 줄 하나를 만들고 막힘과 승인 번호로 답한다")
    void preparesPendingFinancialActionFromPrepareTool() {
        var answer = prepare(UUID.randomUUID().toString(), trade("create_order", ARGS));
        assertThat(answer.allowed()).isFalse();
        assertThat(answer.actionId()).isNotNull();
        var action = action(answer);
        assertThat(action.status()).isEqualTo(ActionStatus.PENDING);
        assertThat(action.risk()).isEqualTo(ToolRisk.FINANCIAL);
        assertThat(action.hermesTool()).isEqualTo(EXECUTION_TOOL);
        assertThat(action.toolName()).isEqualTo("create_order");
        assertThat(rows()).isEqualTo(1);
        assertThat(counters().path("prepare").intValue()).isEqualTo(1);
        assertThat(counters().path("financial").intValue()).isZero();
    }


    @Test
    @DisplayName("같은 tool_call_id 로 다시 부르면 준비 HTTP 없이 같은 승인 번호로 답한다")
    void replaysSameToolCallWithoutPreparingAgain() {
        String toolCallId = UUID.randomUUID().toString();
        var first = prepare(toolCallId, trade("create_order", ARGS));
        var second = prepare(toolCallId, trade("create_order", ARGS));
        assertThat(second.actionId()).isEqualTo(first.actionId());
        assertThat(second.allowed()).isFalse();
        assertThat(rows()).isEqualTo(1);
        assertThat(counters().path("prepare").intValue()).isEqualTo(1);
    }


    @ParameterizedTest
    @ValueSource(strings = {"modify", "version", "duplicate", "extra"})
    @DisplayName("열지 않은 동작, 다른 판, 중복 키와 추가 키가 있는 준비 호출은 줄 없이 막는다")
    void rejectsUnopenedOrMalformedPrepareInput(String change) {
        String body = switch (change) {
            case "modify" -> trade("modify_order", "{\"orderId\":\"old\",\"orderType\":\"LIMIT\",\"quantity\":\"1\",\"price\":\"12\"}");
            case "version" -> trade("create_order", ARGS).replace("\"v\":1", "\"v\":2");
            case "duplicate" -> trade("create_order", ARGS).replace("\"v\":1,", "\"v\":1,\"v\":1,");
            default -> trade("create_order", ARGS).replace("\"v\":1,", "\"v\":1,\"note\":\"x\",");
        };
        var answer = prepare(UUID.randomUUID().toString(), body);
        assertThat(answer.allowed()).isFalse();
        assertThat(answer.actionId()).isNull();
        assertThat(answer.message()).isEqualTo(ConnectorFinancialIntake.FINANCIAL_UNAVAILABLE_MESSAGE);
        assertThat(rows()).isZero();
        assertThat(counters().path("prepare").intValue()).isZero();
    }


    @Test
    @DisplayName("실행 도구 이름으로 직접 부른 호출은 RISK_NOT_OPEN 이다")
    void directExecutionToolCallIsRiskNotOpen() {
        String toolCallId = UUID.randomUUID().toString();
        var answer = publicPolicy.decide(profile, session, session, toolCallId, EXECUTION_TOOL, "create_order", ARGS);
        assertThat(answer.allowed()).isFalse();
        assertThat(answer.actionId()).isNull();
        var recorded = actions.findByDedupeKey(ConnectorActionKey.of(profile, session, session, toolCallId).value()).orElseThrow();
        assertThat(recorded.denyReason()).isEqualTo(ActionDenyReason.RISK_NOT_OPEN);
        assertThat(counters().path("prepare").intValue()).isZero();
    }


    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("읽기 경계와 쓰기를 허용한 살펴보기 실행의 준비 호출은 줄 없이 막고 준비 HTTP 를 보내지 않는다")
    void checkTreeDoesNotPrepare(boolean writesAllowed) {
        Long check = checkTree(writesAllowed);
        try {
            var answer = prepare(UUID.randomUUID().toString(), trade("create_order", ARGS));
            assertThat(answer.allowed()).isFalse();
            assertThat(answer.actionId()).isNull();
            assertThat(rows()).isZero();
            assertThat(counters().path("prepare").intValue()).isZero();
        } finally {
            // 다른 테스트가 RUNNING 살펴보기 줄을 보지 않도록 지운다.
            checks.deleteById(check);
        }
    }


    @Test
    @DisplayName("살펴보기가 아닌 예약 작업 실행의 준비 호출은 PENDING 금융 줄을 만들고 사람이 승인한 뒤에만 금융 POST 를 보낸다")
    void scheduledTaskRunPreparesPendingUntilApproved() {
        Long[] created = new Long[3];
        try {
            tx().executeWithoutResult(status -> {
                var run = runs.findAll().stream().filter(row -> session.equals(row.hermesSessionId())).findFirst().orElseThrow();
                var task = tasks.save(Task.create(user.id(), run.agentId(), "매일 주문", "주문해 줘", ConversationMode.NEW_PER_RUN,
                    NotifyPolicy.NEVER, now));
                var trigger = triggers.save(TaskTrigger.cron(task.id(), "0 9 * * *", ZoneId.of("Asia/Seoul"), MissedPolicy.RUN_ONCE,
                    now, now));
                var taskRun = TaskRun.queued(task.id(), trigger.id(), user.id(), now, now);
                taskRun.useConversation(run.conversationId());
                taskRun.start(now);
                created[0] = taskRuns.save(taskRun).id();
                created[1] = trigger.id();
                created[2] = task.id();
            });
            // 도는 발화는 끝날 때 루트 실행 번호를 적는다. 이 fixture 의 실행이 그 발화의 루트임을 미리 잇는다.
            assertThat(jdbc.update("update task_run set execution_id = (select id from agent_execution where hermes_session_id = ?)"
                + " where id = ?", session, created[0])).isEqualTo(1);
            var requested = prepare(UUID.randomUUID().toString(), trade("create_order", ARGS));
            assertThat(requested.allowed()).isFalse();
            assertThat(action(requested).status()).isEqualTo(ActionStatus.PENDING);
            assertThat(rows()).isEqualTo(1);
            assertThat(counters().path("prepare").intValue()).isEqualTo(1);
            assertThat(counters().path("financial").intValue()).isZero();
            assertThat(claims()).isZero();
            assertThat(publicApproval.approve(user, requested.actionId(), null, false).status()).isEqualTo(ActionStatus.SUCCEEDED);
            assertThat(counters().path("financial").intValue()).isEqualTo(1);
            assertThat(claims()).isEqualTo(1);
        } finally {
            if (created[0] != null) {
                taskRuns.deleteById(created[0]);
                triggers.deleteById(created[1]);
                tasks.deleteById(created[2]);
            }
        }
    }


    @Test
    @DisplayName("공개 승인은 금융 POST 를 한 번 보내고 다시 승인하면 NOT_PENDING 이며 claim 을 재생해도 더 보내지 않는다")
    void publicApprovalSendsOneFinancialPost() {
        var requested = prepare(UUID.randomUUID().toString(), trade("create_order", ARGS));
        var approved = publicApproval.approve(user, requested.actionId(), null, false);
        assertThat(approved.status()).as("목적지 관측=%s", counters()).isEqualTo(ActionStatus.SUCCEEDED);
        assertThat(action(requested).status()).isEqualTo(ActionStatus.SUCCEEDED);
        assertThat(counters().path("financial").intValue()).isEqualTo(1);
        assertThat(claims()).isEqualTo(1);
        assertThatThrownBy(() -> publicApproval.approve(user, requested.actionId(), null, false))
            .isInstanceOf(ApiException.class).extracting("code").isEqualTo(ErrorCode.CONNECTOR_ACTION_NOT_PENDING);
        assertThat(client.executeApproved(profile, "tossinvest", EXECUTION_TOOL, sent).ok()).isFalse();
        assertThat(counters().path("financial").intValue()).isEqualTo(1);
        assertThat(claims()).isEqualTo(1);
    }


    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("금융 줄의 승인에 상시 허락이나 대화 허락을 주면 입력 오류이고, 남의 줄이면 입력보다 먼저 없는 줄이다")
    void financialApprovalTakesNoGrant(boolean standing) {
        var requested = prepare(UUID.randomUUID().toString(), trade("create_order", ARGS));
        GrantPeriod grant = standing ? GrantPeriod.TODAY : null;
        assertThatThrownBy(() -> publicApproval.approve(user, requested.actionId(), grant, !standing))
            .isInstanceOf(ApiException.class).extracting("code").isEqualTo(ErrorCode.VALIDATION_FAILED);
        var other = users.findById(otherUser()).orElseThrow();
        var stranger = new CurrentUser(other.id(), other.email(), "다른 사람", 1L, UserRole.MEMBER);
        assertThatThrownBy(() -> publicApproval.approve(stranger, requested.actionId(), grant, !standing))
            .isInstanceOf(ApiException.class).extracting("code").isEqualTo(ErrorCode.CONNECTOR_ACTION_NOT_FOUND);
        assertThat(action(requested).status()).isEqualTo(ActionStatus.PENDING);
        assertThat(counters().path("financial").intValue()).isZero();
        assertThat(counters().path("execute").intValue()).isZero();
    }


    @Test
    @DisplayName("금융 PENDING 줄을 거절하면 실행하지 않고 REJECTED 로 끝난다")
    void rejectingFinancialActionDoesNotExecute() {
        var requested = prepare(UUID.randomUUID().toString(), trade("create_order", ARGS));
        assertThat(publicApproval.reject(user, requested.actionId()).status()).isEqualTo(ActionStatus.REJECTED);
        assertThat(action(requested).status()).isEqualTo(ActionStatus.REJECTED);
        assertThat(counters().path("execute").intValue()).isZero();
        assertThat(counters().path("financial").intValue()).isZero();
    }


    @Test
    @DisplayName("허용 목록 항목의 메일이 다른 사용자면 준비부터 거절하고, 같은 계좌를 두 항목에 적으면 설정을 만들지 못한다")
    void allowListEntryOfAnotherUserRejectsPreparation() {
        OverridableLiveProperties.during(gate, financial(true, List.of("7:other-" + user.email()), gate.current()));
        var answer = prepare(UUID.randomUUID().toString(), trade("create_order", ARGS));
        assertThat(answer.allowed()).isFalse();
        assertThat(answer.actionId()).isNull();
        assertThat(rows()).isZero();
        assertThat(counters().path("prepare").intValue()).isZero();
        assertThatThrownBy(() -> financial(true, List.of("7:" + user.email(), "7:other-" + user.email()), gate.current()))
            .isInstanceOf(IllegalStateException.class);
    }


    @Test
    @DisplayName("USD 금액 주문은 원화 상한이 0 이어도 USD 한 건 상한 안이면 지나고 넘으면 거절한다")
    void usdAmountOrderUsesUsdLimitOnly() {
        OverridableLiveProperties.during(gate, limits("30", "100"));
        var within = prepare(UUID.randomUUID().toString(), trade("create_order", amountOrder("25")));
        assertThat(action(within).status()).isEqualTo(ActionStatus.PENDING);
        var over = prepare(UUID.randomUUID().toString(), trade("create_order", amountOrder("31")));
        assertThat(over.actionId()).isNull();
        assertThat(rows()).isEqualTo(1);
        assertThat(counters().path("financial").intValue()).isZero();
    }


    @Test
    @DisplayName("하루 합계는 승인한 주문만 세고 PENDING 은 세지 않아 상한을 넘는 준비만 거절한다")
    void dailyTotalCountsApprovedOrdersOnly() {
        OverridableLiveProperties.during(gate, limits("30", "30"));
        var spent = prepare(UUID.randomUUID().toString(), trade("create_order", amountOrder("20")));
        assertThat(publicApproval.approve(user, spent.actionId(), null, false).status()).isEqualTo(ActionStatus.SUCCEEDED);
        // 20 과 더해 31 이라 넘는다.
        var over = prepare(UUID.randomUUID().toString(), trade("create_order", amountOrder("11")));
        assertThat(over.actionId()).isNull();
        assertThat(rows()).isEqualTo(1);
        // 20 과 더해 30 이라 상한 안이다. 이 대조군이 있어야 앞의 거절이 상한 때문임이 드러난다.
        var within = prepare(UUID.randomUUID().toString(), trade("create_order", amountOrder("10")));
        assertThat(action(within).status()).isEqualTo(ActionStatus.PENDING);
        assertThat(rows()).isEqualTo(2);
        assertThat(counters().path("financial").intValue()).isEqualTo(1);
    }


    @Test
    @DisplayName("준비가 사용자 상한에 걸리면 답이 걸린 상한과 오늘 남은 한도를 밝히고 금융 POST 를 보내지 않는다")
    void limitRejectionNamesLimitAndRemaining() {
        OverridableLiveProperties.during(gate, limits("30", "100"));
        // 운영 하루 최대치는 100 이고 사용자는 40 으로 낮췄다.
        jdbc.update("insert into financial_order_limit (user_id, daily_amount_usd, updated_at) values (?, 40, current_timestamp)", user.id());
        var spent = prepare(UUID.randomUUID().toString(), trade("create_order", amountOrder("25")));
        assertThat(publicApproval.approve(user, spent.actionId(), null, false).status()).isEqualTo(ActionStatus.SUCCEEDED);
        var over = prepare(UUID.randomUUID().toString(), trade("create_order", amountOrder("20")));
        assertThat(over.allowed()).isFalse();
        assertThat(over.actionId()).isNull();
        assertThat(over.message()).contains("하루 달러 합계 40 USD").contains("오늘 남은 한도: 15 USD").contains("order_limit_change");
        assertThat(rows()).isEqualTo(1);
        assertThat(counters().path("financial").intValue()).isEqualTo(1);
        // 한 건 상한은 운영 최대치 30 이다. 남은 한도는 같은 하루 합계에서 낸다.
        var single = prepare(UUID.randomUUID().toString(), trade("create_order", amountOrder("31")));
        assertThat(single.message()).contains("한 건 달러 금액 30 USD").contains("오늘 남은 한도: 15 USD");
    }


    @Test
    @DisplayName("주문 승인 카드 요약은 그 통화의 한 건 상한, 하루 상한, 오늘 쓴 금액과 남은 한도를 싣는다")
    void summaryCarriesLimitStatus() {
        OverridableLiveProperties.during(gate, limits("30", "100"));
        jdbc.update("insert into financial_order_limit (user_id, daily_amount_usd, updated_at) values (?, 50, current_timestamp)", user.id());
        var spent = prepare(UUID.randomUUID().toString(), trade("create_order", amountOrder("20")));
        assertThat(publicApproval.approve(user, spent.actionId(), null, false).status()).isEqualTo(ActionStatus.SUCCEEDED);
        var pending = prepare(UUID.randomUUID().toString(), trade("create_order", amountOrder("10")));
        var limits = summaries.summaryOf(user, pending.actionId()).orElseThrow().limits();
        assertThat(limits.currency()).isEqualTo("USD");
        assertThat(limits.orderLimit()).isEqualTo("30");
        assertThat(limits.quantityLimit()).isEqualTo("1000");
        assertThat(limits.dailyLimit()).isEqualTo("50");
        assertThat(limits.spentToday()).isEqualTo("20");
        assertThat(limits.remainingToday()).isEqualTo("30");
    }


    @Test
    @DisplayName("가격 없는 수량 주문은 금액을 미리 알 수 없어 거절한다")
    void marketQuantityOrderWithoutPriceIsRejected() {
        var answer = prepare(UUID.randomUUID().toString(),
            trade("create_order", "{\"symbol\":\"ABC\",\"side\":\"SELL\",\"orderType\":\"MARKET\",\"quantity\":\"0.1\"}"));
        assertThat(answer.allowed()).isFalse();
        assertThat(answer.actionId()).isNull();
        assertThat(rows()).isZero();
        assertThat(counters().path("financial").intValue()).isZero();
    }


    @Test
    @DisplayName("요약은 저장한 summary 를 옮기고 계좌는 scope 의 순번을 실어, summary 계좌가 같아도 scope 가 다르면 다른 순번이다")
    void summarizesWithScopeAccount() {
        var first = prepare(UUID.randomUUID().toString(), trade("create_order", ARGS));
        var stored = action(first);
        var snapshot = ConnectorExecutionSnapshot.open(stored, contents.findById(stored.id()).orElseThrow(), cipher, digest);
        assertThat(snapshot.executionArgsJson()).contains("\"account_seq\":\"7\"");
        Long[] other = new Long[2];
        try {
            var second = tx().execute(status -> {
                var connection = ConnectorConnection.pending(user.id(), "demo-financial", now);
                connection.connected(new ConnectionFields(Map.of("account", "8"), Map.of()), now);
                connection.ready(now);
                connections.saveAndFlush(connection);
                var binding = ConnectorBinding.pending(bindings.findById(bindingId).orElseThrow().agent(), connection, "demo", now);
                binding.installed(false, now);
                binding.ready(now);
                bindings.saveAndFlush(binding);
                other[0] = binding.id();
                other[1] = connection.id();
                var run = runs.findAll().stream().filter(row -> session.equals(row.hermesSessionId())).findFirst().orElseThrow();
                var action = ConnectorAction.decided(connection, binding.agent().id(), run, "mcp__demo__create_order", "create_order",
                    new ToolPolicyDecision(ActionDecision.NEEDS_APPROVAL, null, ToolRisk.FINANCIAL, ToolApproval.ALWAYS), false,
                    Sha256.hex(UUID.randomUUID().toString()), digest.of("args_json", ARGS), now);
                action.awaitApproval(now.plusSeconds(300));
                actions.saveAndFlush(action);
                bodies.sealArgs(action, ARGS);
                contents.saveAndFlush(ConnectorExecutionSnapshot.capture(action, binding, "CREATE",
                    "[{\"arg\":\"account_seq\",\"field\":\"account\"}]",
                    snapshot.executionArgsJson().replace("\"account_seq\":\"7\"", "\"account_seq\":\"8\""), snapshot.summaryJson(),
                    "{\"account_seq\":\"8\"}", cipher, now, digest));
                return action.publicId();
            });
            var found = summaries.summaries(user, List.of(first.actionId(), second, UUID.randomUUID()));
            assertThat(found).containsOnlyKeys(first.actionId(), second);
            var summary = found.get(first.actionId());
            assertThat(summary.available()).isTrue();
            assertThat(summary.accountSeq()).isEqualTo("7");
            assertThat(found.get(second).accountSeq()).isEqualTo("8");
            assertThat(summary.operation()).isEqualTo("CREATE");
            assertThat(summary.symbol()).isEqualTo("ABC");
            assertThat(summary.market()).isEqualTo("NASDAQ");
            assertThat(summary.currency()).isEqualTo("USD");
            assertThat(summary.side()).isEqualTo("BUY");
            assertThat(summary.quantity()).isEqualTo("2");
            assertThat(summary.orderAmount()).isNull();
            assertThat(summary.price()).isEqualTo("12.34");
            assertThat(summary.orderType()).isEqualTo("LIMIT");
            assertThat(summary.timeInForce()).isEqualTo("DAY");
            assertThat(summary.orderId()).isNull();
            assertThat(summary.normalization()).contains(
                new FinancialActionSummary.Normalization("symbol", "abc", "ABC"),
                new FinancialActionSummary.Normalization("timeInForce", null, "DAY"),
                new FinancialActionSummary.Normalization("price", "0012.3400", "12.34"));
            var stranger = new CurrentUser(otherUser(), "stranger@example.test", "다른 사람", 1L, UserRole.MEMBER);
            assertThat(summaries.summaries(stranger, List.of(first.actionId()))).isEmpty();
            assertThat(summaries.summaryOf(stranger, first.actionId())).isEmpty();
        } finally {
            jdbc.update("delete from connector_action where user_id = ?", user.id());
            if (other[0] != null) {
                bindings.deleteById(other[0]);
                connections.deleteById(other[1]);
            }
        }
    }


    @Test
    @DisplayName("암호문을 망가뜨린 줄은 요약이 available=false 이고 승인해도 금융 POST 를 보내지 않는다")
    void tamperedSnapshotIsUnavailableAndNotExecuted() {
        var requested = prepare(UUID.randomUUID().toString(), trade("create_order", ARGS));
        jdbc.update("update connector_action_execution set summary_json = execution_args_json where action_id = ?",
            action(requested).id());
        var summary = summaries.summaryOf(user, requested.actionId()).orElseThrow();
        assertThat(summary.available()).isFalse();
        assertThat(summary.accountSeq()).isNull();
        assertThatThrownBy(() -> publicApproval.approve(user, requested.actionId(), null, false)).isInstanceOf(ApiException.class);
        assertThat(action(requested).status()).isEqualTo(ActionStatus.PENDING);
        assertThat(counters().path("financial").intValue()).isZero();
        assertThat(claims()).isZero();
    }


    ConnectorPolicyAnswer prepare(String toolCallId, String body) {
        return publicPolicy.decide(profile, session, session, toolCallId, PREPARE_TOOL, "prepare_trade", body);
    }


    /** 이 사용자에게 계좌를 열고 원화 상한은 0, USD 한 건 상한과 하루 상한만 둔다. */
    ConnectorFinancialProperties limits(String maxOrderUsd, String maxDailyUsd) {
        return new ConnectorFinancialProperties(owned("7"), BigDecimal.ZERO, new BigDecimal(maxOrderUsd), new BigDecimal("1000"),
            BigDecimal.ZERO, new BigDecimal(maxDailyUsd), TEST_DIGEST_KEY);
    }


    static ConnectorFinancialProperties with(ConnectorFinancialProperties base, List<String> accounts, BigDecimal maxOrderUsd,
        String keyFile) {
        return new ConnectorFinancialProperties(accounts, base.maxOrderAmountKrw(), maxOrderUsd, base.maxOrderQuantity(),
            base.maxDailyAmountKrw(), base.maxDailyAmountUsd(), keyFile);
    }


    /** 대역 토스는 종목 ABC(NASDAQ, USD) 하나만 돌려준다. */
    static String amountOrder(String amount) {
        return "{\"symbol\":\"ABC\",\"side\":\"BUY\",\"orderType\":\"MARKET\",\"orderAmount\":\"" + amount + "\"}";
    }


    static String trade(String tool, String args) {
        return "{\"v\":1,\"tool\":\"" + tool + "\",\"args\":" + args + "}";
    }


    long rows() {
        return jdbc.queryForObject("select count(*) from connector_action where user_id = ?", Long.class, user.id());
    }


    /** 이 사용자의 금융 줄 가운데 claim 이 ticket 을 소비한 수다. */
    long claims() {
        return jdbc.queryForObject("select count(*) from connector_action_execution e join connector_action a on a.id = e.action_id"
            + " where a.user_id = ? and e.consumed_at is not null", Long.class, user.id());
    }
}
