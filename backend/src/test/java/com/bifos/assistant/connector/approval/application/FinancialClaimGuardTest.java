package com.bifos.assistant.connector.approval.application;

import static com.bifos.assistant.connector.approval.application.FinancialOrderOpeningTest.PREPARE_TOOL;
import static com.bifos.assistant.connector.approval.application.FinancialOrderOpeningTest.amountOrder;
import static com.bifos.assistant.connector.approval.application.FinancialOrderOpeningTest.trade;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import com.bifos.assistant.connector.action.domain.type.ActionStatus;
import com.bifos.assistant.connector.action.domain.type.FinancialLimitKind;
import com.bifos.assistant.connector.connection.application.dto.ConnectorPolicyAnswer;
import com.bifos.assistant.connector.financial.application.ConnectorExecutionSnapshot;
import com.bifos.assistant.connector.financial.application.ConnectorExecutionTicket;
import com.bifos.assistant.connector.financial.application.FinancialLimitSettings;
import com.bifos.assistant.connector.financial.config.ConnectorFinancialProperties;
import com.bifos.assistant.crypto.domain.TextCipher;
import com.bifos.assistant.feedback.domain.FeedbackEvent;
import com.bifos.assistant.feedback.domain.type.FeedbackEventType;
import com.bifos.assistant.feedback.domain.type.FeedbackSubjectType;
import com.bifos.assistant.feedback.infra.FeedbackEventRepository;
import com.bifos.assistant.hermes.application.dto.CallResult;
import com.bifos.assistant.hermes.application.dto.ConnectorApprovedExecution;
import com.bifos.assistant.testsupport.OverridableLiveProperties;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * claim 이 최신 주문 상한으로 다시 판정하는지(#590), claim 없이 온 성공 응답을 성공으로 적지 않는지(#591) 실제 plugin 왕복으로 본다.
 *
 * <p>금융 목적지는 loopback 대역이다. 실제 토스 API 와 금융 서비스는 부르지 않는다.
 */
@ExtendWith(OutputCaptureExtension.class)
class FinancialClaimGuardTest extends FinancialConnectorTestBase {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    @Autowired
    ConnectorPolicyService publicPolicy;
    @Autowired
    ConnectorActionService publicApproval;
    @Autowired
    FinancialLimitSettings limitSettings;
    @Autowired
    ConnectorExecutionTicket tickets;
    @Autowired
    TextCipher cipher;
    @Autowired
    FeedbackEventRepository feedback;


    @ParameterizedTest
    @DisplayName("승인 뒤 claim 전에 상한을 낮추면 claim 이 최신 상한으로 거절해 ticket 을 소비하지 않고 금융 POST 도 없다")
    @CsvSource({"none,,SUCCEEDED", "order_amount_usd,10,FAILED", "daily_amount_usd,15,FAILED", "daily_amount_usd,20,SUCCEEDED"})
    void claimRechecksLatestLimitAfterApproval(String kind, String value, ActionStatus expected) {
        // 운영 최대치는 한 건 30 USD, 하루 100 USD 다. 주문은 20 USD 라 승인은 지난다.
        OverridableLiveProperties.during(gate, limits("30", "100"));
        var requested = prepare(amountOrder("20"));
        doAnswer(invocation -> {
            sent = invocation.getArgument(3);
            if (!"none".equals(kind)) {
                // 승인 트랜잭션이 커밋된 뒤, claim 전에 계좌 주인이 화면에서 상한을 낮춘다.
                limitSettings.set(user, FinancialLimitKind.valueOf(kind.toUpperCase(Locale.ROOT)), new BigDecimal(value),
                    clock.instant());
            }
            return client.executeApproved(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2), sent);
        }).when(hermes).executeApproved(anyString(), anyString(), anyString(), any());
        assertThat(publicApproval.approve(user, requested.actionId(), null, false).status()).isEqualTo(expected);
        boolean sentOrder = expected == ActionStatus.SUCCEEDED;
        // 하루 상한 20 이 20 USD 주문을 받는 것은 claim 이 자기 EXECUTING 줄을 하루 합계에 다시 세지 않기 때문이다.
        assertThat(contents.findById(action(requested).id()).orElseThrow().consumedAt() != null).isEqualTo(sentOrder);
        assertThat(claims()).isEqualTo(sentOrder ? 1 : 0);
        assertThat(counters().path("financial").intValue()).isEqualTo(sentOrder ? 1 : 0);
        assertThat(sent).isNotNull();
    }


    @ParameterizedTest
    @DisplayName("상한 변경과 claim 은 같은 사용자 행 잠금에서 차례로 일어나 먼저 커밋한 쪽이 판정을 정한다")
    @ValueSource(booleans = {false, true})
    void serializesLimitChangeAndClaimOnUserLock(boolean claimFirst) throws Exception {
        OverridableLiveProperties.during(gate, limits("30", "100"));
        var requested = prepare(amountOrder("20"));
        tx().executeWithoutResult(status -> actions.findByPublicIdForUpdate(requested.actionId()).orElseThrow()
            .beginExecution(clock.instant()));
        String ticket = tickets.issue(requested.actionId());
        var snapshot = ConnectorExecutionSnapshot.open(action(requested), contents.findById(action(requested).id()).orElseThrow(),
            cipher, digest);
        var execution = new ConnectorApprovedExecution(ticket, snapshot.executionArgsJson(), snapshot.executionArgsSha256());
        var locked = new CountDownLatch(1);
        var waiting = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        var calls = new AtomicInteger();
        doAnswer(invocation -> {
            int turn = calls.incrementAndGet();
            if (turn == 2) {
                waiting.countDown();
            }
            jdbc.queryForObject("select id from app_user where id = ? for update", Long.class, user.id());
            if (turn == 1) {
                locked.countDown();
                assertThat(resume.await(15, TimeUnit.SECONDS)).isTrue();
            }
            return users.findById(user.id());
        }).when(users).findByIdForUpdate(user.id());
        Callable<Object> claiming = () -> client.executeApproved(profile, "tossinvest", "mcp__tossinvest__create_order", execution);
        Callable<Object> lowering = () -> limitSettings.set(user, FinancialLimitKind.ORDER_AMOUNT_USD, new BigDecimal("10"),
            clock.instant());
        CallResult claimed;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(claimFirst ? claiming : lowering);
            try {
                assertThat(locked.await(15, TimeUnit.SECONDS)).isTrue();
                var second = executor.submit(claimFirst ? lowering : claiming);
                try {
                    assertThat(waiting.await(15, TimeUnit.SECONDS)).isTrue();
                    assertThat(second.isDone()).isFalse();
                } finally {
                    resume.countDown();
                }
                Object firstResult = first.get(20, TimeUnit.SECONDS);
                Object secondResult = second.get(20, TimeUnit.SECONDS);
                claimed = (CallResult)(claimFirst ? firstResult : secondResult);
            } finally {
                resume.countDown();
            }
        }
        assertThat(claimed.ok()).isEqualTo(claimFirst);
        assertThat(contents.findById(action(requested).id()).orElseThrow().consumedAt() != null).isEqualTo(claimFirst);
        assertThat(counters().path("financial").intValue()).isEqualTo(claimFirst ? 1 : 0);
        assertThat(jdbc.queryForObject("select limit_order_amount_usd from app_user where id = ?", BigDecimal.class, user.id()))
            .isEqualByComparingTo("10");
    }


    @ParameterizedTest
    @DisplayName("KST 자정을 넘겨 claim 해도 하루 합계는 승인한 날로 다시 판정한다")
    @ValueSource(booleans = {false, true})
    void dailyBudgetAtClaimUsesApprovalDay(boolean lowered) {
        // 커넥터가 ticket 시각을 실제 시각과 비교하므로 시계를 옮기지 않고 승인 시각을 어제 KST 끝으로 옮긴다.
        Instant midnight = clock.instant().atZone(KST).toLocalDate().atStartOfDay(KST).toInstant();
        OverridableLiveProperties.during(gate, limits("30", "30"));
        var earlier = prepare(amountOrder("20"));
        assertThat(publicApproval.approve(user, earlier.actionId(), null, false).status()).isEqualTo(ActionStatus.SUCCEEDED);
        var requested = prepare(amountOrder("10"));
        doAnswer(invocation -> {
            sent = invocation.getArgument(3);
            jdbc.update("update connector_action set decided_at = ? where id = ?", Timestamp.from(midnight.minusSeconds(60)),
                action(earlier).id());
            jdbc.update("update connector_action set decided_at = ? where id = ?", Timestamp.from(midnight.minusSeconds(10)),
                action(requested).id());
            if (lowered) {
                // 승인한 날의 합계는 20 + 10 = 30 이다. 25 로 낮추면 넘는다. claim 한 오늘로 셌다면 10 이라 지난다.
                limitSettings.set(user, FinancialLimitKind.DAILY_AMOUNT_USD, new BigDecimal("25"), clock.instant());
            }
            return client.executeApproved(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2), sent);
        }).when(hermes).executeApproved(anyString(), anyString(), anyString(), any());
        assertThat(publicApproval.approve(user, requested.actionId(), null, false).status())
            .isEqualTo(lowered ? ActionStatus.FAILED : ActionStatus.SUCCEEDED);
        assertThat(contents.findById(action(requested).id()).orElseThrow().consumedAt() != null).isEqualTo(!lowered);
        assertThat(counters().path("financial").intValue()).isEqualTo(lowered ? 1 : 2);
    }


    @Test
    @DisplayName("실행기가 claim 없이 성공을 돌려주면 SUCCEEDED 와 실행 성공 피드백 없이 UNKNOWN 으로 남기고 다시 보내지 않는다")
    void unclaimedSuccessIsProtocolMismatch(CapturedOutput output) {
        var requested = request();
        doAnswer(invocation -> {
            sent = invocation.getArgument(3);
            // claim 을 부르지 않고 성공만 돌려주는 실행기다. 금융 목적지에는 아무것도 가지 않는다.
            return CallResult.success(JSON.readTree("{\"orderId\":\"forged-official-id\"}"));
        }).when(hermes).executeApproved(anyString(), anyString(), anyString(), any());
        assertThat(approval.approve(user, requested.actionId()).status()).isEqualTo(ActionStatus.UNKNOWN);
        var action = action(requested);
        assertThat(action.resultText()).isNull();
        assertThat(contents.findById(action.id()).orElseThrow().consumedAt()).isNull();
        assertThat(events(action.publicId())).containsExactly(FeedbackEventType.SURFACED, FeedbackEventType.APPROVED);
        assertThat(counters().path("financial").intValue()).isZero();
        // 남은 ticket 으로 claim 해도 줄이 EXECUTING 이 아니라 거절된다. 같은 의도의 새 준비도 UNKNOWN 이 막는다.
        assertThat(client.executeApproved(profile, "tossinvest", "mcp__tossinvest__create_order", sent).ok()).isFalse();
        assertThatThrownBy(this::request).isInstanceOf(RuntimeException.class);
        assertThat(counters().path("financial").intValue()).isZero();
        // 진단 줄은 승인 번호만 싣는다. 원문 인자, ticket, 위조 결과를 남기지 않는다.
        assertThat(output.getAll()).contains("금융 실행 프로토콜 불일치").contains(requested.actionId().toString())
            .doesNotContain(sent.ticket()).doesNotContain(sent.argsJson()).doesNotContain("forged-official-id");
    }


    @Test
    @DisplayName("실제 claim 뒤 성공은 지금처럼 SUCCEEDED 와 실행 성공 피드백을 남긴다")
    void claimedSuccessRecordsSuccess() {
        var requested = request();
        assertThat(approval.approve(user, requested.actionId()).status()).isEqualTo(ActionStatus.SUCCEEDED);
        assertThat(contents.findById(action(requested).id()).orElseThrow().consumedAt()).isNotNull();
        assertThat(events(action(requested).publicId())).containsExactly(FeedbackEventType.SURFACED, FeedbackEventType.APPROVED,
            FeedbackEventType.EXECUTION_SUCCEEDED);
        assertThat(counters().path("financial").intValue()).isEqualTo(1);
    }


    ConnectorPolicyAnswer prepare(String args) {
        return publicPolicy.decide(profile, session, session, UUID.randomUUID().toString(), PREPARE_TOOL, "prepare_trade",
            trade("create_order", args));
    }


    /** 이 사용자에게 계좌를 열고 원화 상한은 0, USD 한 건 상한과 하루 상한만 둔다. */
    ConnectorFinancialProperties limits(String maxOrderUsd, String maxDailyUsd) {
        return new ConnectorFinancialProperties(owned("7"), BigDecimal.ZERO, new BigDecimal(maxOrderUsd), new BigDecimal("1000"),
            BigDecimal.ZERO, new BigDecimal(maxDailyUsd), TEST_DIGEST_KEY);
    }


    /** 이 사용자의 금융 줄 가운데 claim 이 ticket 을 소비한 수다. */
    long claims() {
        return jdbc.queryForObject("select count(*) from connector_action_execution e join connector_action a on a.id = e.action_id"
            + " where a.user_id = ? and e.consumed_at is not null", Long.class, user.id());
    }


    List<FeedbackEventType> events(UUID actionId) {
        return feedback.findByUserIdAndOccurredAtGreaterThanEqualOrderByOccurredAtAscIdAsc(user.id(), now.minus(Duration.ofDays(1)))
            .stream().filter(event -> FeedbackSubjectType.CONNECTOR_ACTION.key(actionId).equals(event.subjectKey())).map(FeedbackEvent::eventType).toList();
    }
}
