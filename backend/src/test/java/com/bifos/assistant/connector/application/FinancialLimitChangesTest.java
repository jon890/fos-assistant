package com.bifos.assistant.connector.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.connector.domain.type.ActionStatus;
import com.bifos.assistant.connector.domain.type.FinancialLimitKind;
import com.bifos.assistant.connector.domain.type.GrantPeriod;
import com.bifos.assistant.connector.connection.domain.type.ToolRisk;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
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
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

/** 에이전트가 대화로 주문 상한을 바꾸는 길이다. 낮추기는 바로, 올리기는 승인 카드를 거쳐야만 반영된다. */
class FinancialLimitChangesTest extends FinancialConnectorTestBase {
    @Autowired
    FinancialLimitChanges changes;
    @Autowired
    FinancialOrderLimits limits;
    @Autowired
    ConnectorActionService publicApproval;
    @Autowired
    TaskRepository tasks;
    @Autowired
    TaskTriggerRepository triggers;
    @Autowired
    TaskRunRepository taskRuns;
    Long origin;


    @BeforeEach
    void openLimits() {
        // 운영 최대치: 원화 한 건 10만, 달러 한 건 1000, 수량 10, 원화 하루 30만, 달러 하루 1000.
        OverridableLiveProperties.during(gate, maximums("1000"));
        origin = jdbc.queryForObject("select id from agent_execution where hermes_session_id = ?", Long.class, session);
    }


    @Test
    @DisplayName("낮추기는 승인 줄 없이 바로 반영된다")
    void lowersImmediately() {
        var result = changes.change(user, origin, "order_amount_usd", "300");
        assertThat(result).containsEntry("status", "applied").containsEntry("value", "300").containsEntry("previous", "1000");
        assertThat(usd()).isEqualByComparingTo("300");
        assertThat(rows()).isZero();
    }


    @Test
    @DisplayName("올리기는 PENDING 승인 줄만 만들고 상한은 그대로이며, 사용자가 승인해야 반영된다")
    void raisingWaitsForApproval() {
        changes.change(user, origin, "order_amount_usd", "300");
        var result = changes.change(user, origin, "order_amount_usd", "500");
        assertThat(result).containsEntry("status", "approval_requested").containsEntry("previous", "300")
            .containsEntry("requested", "500").containsEntry("maximum", "1000");
        assertThat(usd()).isEqualByComparingTo("300");
        UUID actionId = UUID.fromString((String)result.get("actionId"));
        var pending = actions.findByPublicId(actionId).orElseThrow();
        assertThat(pending.status()).isEqualTo(ActionStatus.PENDING);
        assertThat(pending.risk()).isEqualTo(ToolRisk.WRITE);
        assertThat(pending.grantAllowed()).isFalse();
        assertThat(changes.summaryOf(user, actionId).orElseThrow().requested()).isEqualTo("500");
        // 같은 실행의 같은 요청은 같은 승인 줄이다.
        assertThat(changes.change(user, origin, "order_amount_usd", "500")).containsEntry("actionId", actionId.toString());
        assertThat(rows()).isEqualTo(1);
        var approved = publicApproval.approve(user, actionId, null, false);
        assertThat(approved.status()).isEqualTo(ActionStatus.SUCCEEDED);
        assertThat(approved.title()).isEqualTo("주문 상한 변경");
        assertThat(usd()).isEqualByComparingTo("500");
    }


    @Test
    @DisplayName("최대치를 넘는 값은 승인 줄 없이 거절하고 최대치를 알린다")
    void rejectsAboveMaximumWithoutCard() {
        var result = changes.change(user, origin, "order_amount_usd", "1000.5");
        assertThat(result).containsEntry("status", "above_maximum").containsEntry("maximum", "1000");
        assertThat(usd()).isEqualByComparingTo("1000");
        assertThat(rows()).isZero();
    }


    @Test
    @DisplayName("거절하거나 만료된 올리기는 반영되지 않는다")
    void rejectedOrExpiredRaiseDoesNotApply() {
        changes.change(user, origin, "order_amount_usd", "300");
        UUID rejected = UUID.fromString((String)changes.change(user, origin, "order_amount_usd", "400").get("actionId"));
        assertThat(publicApproval.reject(user, rejected).status()).isEqualTo(ActionStatus.REJECTED);
        // 같은 실행의 같은 요청은 끝난 카드를 기다리는 카드처럼 안내하지 않는다.
        assertThat(changes.change(user, origin, "order_amount_usd", "400")).containsEntry("status", "closed")
            .containsEntry("actionStatus", "REJECTED");
        UUID expired = UUID.fromString((String)changes.change(user, origin, "order_amount_usd", "500").get("actionId"));
        clock.set(now.plus(Duration.ofDays(2)));
        assertThatThrownBy(() -> publicApproval.approve(user, expired, null, false))
            .isInstanceOf(ApiException.class).extracting("code").isEqualTo(ErrorCode.CONNECTOR_ACTION_NOT_PENDING);
        assertThat(usd()).isEqualByComparingTo("300");
    }


    @Test
    @DisplayName("요청 뒤 최대치가 내려가 넘게 되면 승인이 반영하지 않고 줄은 FAILED 다")
    void approvalRechecksMaximum() {
        changes.change(user, origin, "order_amount_usd", "300");
        UUID actionId = UUID.fromString((String)changes.change(user, origin, "order_amount_usd", "800").get("actionId"));
        OverridableLiveProperties.during(gate, maximums("500"));
        var approved = publicApproval.approve(user, actionId, null, false);
        assertThat(approved.status()).isEqualTo(ActionStatus.FAILED);
        assertThat(approved.errorCode()).isEqualTo(ErrorCode.FINANCIAL_LIMIT_ABOVE_MAXIMUM.name());
        assertThat(usd()).isEqualByComparingTo("300");
    }


    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("상한 변경 승인에 상시 허락이나 대화 허락을 주면 입력 오류이고 반영되지 않는다")
    void approvalTakesNoGrant(boolean standing) {
        changes.change(user, origin, "order_amount_usd", "300");
        UUID actionId = UUID.fromString((String)changes.change(user, origin, "order_amount_usd", "500").get("actionId"));
        assertThatThrownBy(() -> publicApproval.approve(user, actionId, standing ? GrantPeriod.HOUR : null, !standing))
            .isInstanceOf(ApiException.class).extracting("code").isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(actions.findByPublicId(actionId).orElseThrow().status()).isEqualTo(ActionStatus.PENDING);
        assertThat(usd()).isEqualByComparingTo("300");
    }


    @Test
    @DisplayName("살펴보기가 아닌 예약 작업 실행이 부른 올리기도 승인 줄만 만들고 승인 전에는 반영되지 않는다")
    void scheduledTaskRaiseOnlyCreatesCard() {
        Long[] created = new Long[3];
        try {
            tx().executeWithoutResult(status -> {
                var run = runs.findById(origin).orElseThrow();
                var task = tasks.save(Task.create(user.id(), run.agentId(), "매일 상한", "상한 올려 줘", ConversationMode.NEW_PER_RUN,
                    NotifyPolicy.NEVER));
                var trigger = triggers.save(TaskTrigger.cron(task.id(), "0 9 * * *", ZoneId.of("Asia/Seoul"), MissedPolicy.RUN_ONCE,
                    now));
                var taskRun = TaskRun.queued(task.id(), trigger.id(), user.id(), now, now);
                taskRun.useConversation(run.conversationId());
                taskRun.start(now);
                created[0] = taskRuns.save(taskRun).id();
                created[1] = trigger.id();
                created[2] = task.id();
            });
            assertThat(jdbc.update("update task_run set execution_id = ? where id = ?", origin, created[0])).isEqualTo(1);
            changes.change(user, origin, "daily_amount_usd", "100");
            var result = changes.change(user, origin, "daily_amount_usd", "900");
            assertThat(result).containsEntry("status", "approval_requested");
            assertThat(limits.effective(user.id()).value(FinancialLimitKind.DAILY_AMOUNT_USD)).isEqualByComparingTo("100");
            UUID actionId = UUID.fromString((String)result.get("actionId"));
            assertThat(publicApproval.approve(user, actionId, null, false).status()).isEqualTo(ActionStatus.SUCCEEDED);
            assertThat(limits.effective(user.id()).value(FinancialLimitKind.DAILY_AMOUNT_USD)).isEqualByComparingTo("900");
        } finally {
            if (created[0] != null) {
                taskRuns.deleteById(created[0]);
                triggers.deleteById(created[1]);
                tasks.deleteById(created[2]);
            }
        }
    }


    @Test
    @DisplayName("승인 줄의 종류와 요청 값, 이전 값은 엔티티를 고쳐 저장해도, 승인한 뒤에도 바뀌지 않는다")
    void limitFieldsAreImmutable() {
        changes.change(user, origin, "order_amount_usd", "300");
        UUID actionId = UUID.fromString((String)changes.change(user, origin, "order_amount_usd", "500").get("actionId"));
        tx().executeWithoutResult(status -> {
            var action = actions.findByPublicId(actionId).orElseThrow();
            assertThatThrownBy(() -> action.recordLimitChange(FinancialLimitKind.DAILY_AMOUNT_USD, BigDecimal.TEN, BigDecimal.ONE))
                .isInstanceOf(IllegalStateException.class);
            ReflectionTestUtils.setField(action, "limitKind", FinancialLimitKind.DAILY_AMOUNT_USD);
            ReflectionTestUtils.setField(action, "limitRequested", new BigDecimal("999"));
            ReflectionTestUtils.setField(action, "limitPrevious", BigDecimal.ONE);
        });
        assertThat(limitColumns(actionId)).containsExactly("ORDER_AMOUNT_USD", "500", "300");
        assertThat(publicApproval.approve(user, actionId, null, false).status()).isEqualTo(ActionStatus.SUCCEEDED);
        assertThat(usd()).isEqualByComparingTo("500");
        assertThat(limitColumns(actionId)).containsExactly("ORDER_AMOUNT_USD", "500", "300");
    }


    @Test
    @DisplayName("허용 목록에 계좌가 없는 사용자는 바꾸지 못하고 읽기는 available 이 거짓이다")
    void userWithoutAccountCannotChange() {
        OverridableLiveProperties.during(gate, new ConnectorFinancialProperties(List.of(), new BigDecimal("100000"),
            new BigDecimal("1000"), new BigDecimal("10"), new BigDecimal("300000"), new BigDecimal("1000"), TEST_DIGEST_KEY));
        assertThat(changes.change(user, origin, "order_amount_usd", "1")).isEqualTo(Map.of("status", "unavailable"));
        assertThat(changes.read(user)).containsEntry("available", false);
    }


    private BigDecimal usd() {
        return limits.effective(user.id()).value(FinancialLimitKind.ORDER_AMOUNT_USD);
    }


    private List<String> limitColumns(UUID actionId) {
        return actions.findByPublicId(actionId).map(action -> List.of(action.limitKind().name(),
            FinancialOrderLimits.plain(action.limitRequested()), FinancialOrderLimits.plain(action.limitPrevious()))).orElseThrow();
    }


    private long rows() {
        return jdbc.queryForObject("select count(*) from connector_action where user_id = ?", Long.class, user.id());
    }


    private ConnectorFinancialProperties maximums(String usd) {
        return new ConnectorFinancialProperties(owned("7"), new BigDecimal("100000"), new BigDecimal(usd), new BigDecimal("10"),
            new BigDecimal("300000"), new BigDecimal("1000"), TEST_DIGEST_KEY);
    }
}
