package com.bifos.assistant.connector.approval.application;

import java.sql.Timestamp;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

import com.bifos.assistant.browser.application.UserBrowserService;
import com.bifos.assistant.browser.domain.UserBrowser;
import com.bifos.assistant.browser.infra.UserBrowserRepository;
import com.bifos.assistant.chat.turn.application.dto.ConversationTurnStarted;
import com.bifos.assistant.connector.action.application.ConnectorBrowserKeptTabs;
import com.bifos.assistant.connector.action.domain.type.ActionStatus;
import com.bifos.assistant.connector.action.domain.type.HandoffNeed;
import com.bifos.assistant.connector.connection.application.ConnectorCatalogCache;
import com.bifos.assistant.connector.connection.application.dto.ConnectorPolicyAnswer;
import com.bifos.assistant.connector.connection.domain.type.ToolRisk;
import com.bifos.assistant.connector.financial.application.ConnectorExecutionClaims;
import com.bifos.assistant.connector.financial.application.PurchaseLimitSettings;
import com.bifos.assistant.connector.financial.application.dto.ConnectorExecutionClaimInput;
import com.bifos.assistant.connector.financial.domain.type.PurchaseLimitKind;
import com.bifos.assistant.connector.handoff.application.ConnectorHandoffs;
import com.bifos.assistant.connector.handoff.application.dto.HandoffView;
import com.bifos.assistant.hermes.application.dto.CallResult;
import com.bifos.assistant.hermes.application.dto.ConnectorApprovedExecution;
import com.bifos.assistant.hermes.application.dto.ConnectorCallError;
import com.bifos.assistant.hermes.application.dto.ConnectorErrorDetail;
import com.bifos.assistant.hermes.application.dto.ConnectorManifest;
import com.bifos.assistant.hermes.application.dto.ConnectorTool;
import com.bifos.assistant.notification.domain.type.NotificationKind;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 주문서 준비({@code CHECKOUT})가 금융 준비·승인·claim 틀을 지나며 구매 상한을 네 번 다시 보고, 결과에 따라 결제나 탭 확인을 넘기는지
 * 공개 진입점(정책 판정과 승인 API)으로 본다(spec REQ10~REQ12, ADR-20261113 / purchase-checkout).
 *
 * <p>대역 토스 플러그인의 연결 상태(보호 지원 확인)는 그대로 쓰고, 카탈로그는 실행 도구를 {@code PURCHASE}/{@code CHECKOUT} 으로
 * 바꾼 선언으로 읽는다. 준비와 채우기 응답은 이 검사가 정한다. 채우기 대역은 실제 claim 서비스를 부른 뒤 결과를 돌려준다.
 */
class CheckoutApprovalTest extends FinancialConnectorTestBase {
    static final String PREPARE_TOOL = "mcp__tossinvest__prepare_trade";
    static final String TAB = "TAB0123456789";
    /** 결제 넘기기 맥락을 받는다고 바꿔 선언하는 읽기 도구다. */
    static final String ORDER_CHECK = "list_orders";
    @Autowired
    ConnectorPolicyService publicPolicy;
    @Autowired
    ConnectorActionService publicApproval;
    @Autowired
    ConnectorCatalogCache cache;
    @Autowired
    ConnectorExecutionClaims claims;
    @Autowired
    PurchaseLimitSettings purchaseLimits;
    @Autowired
    ConnectorHandoffs handoffs;
    @Autowired
    ConnectorFinancialSummaries summaries;
    @Autowired
    ConnectorApprovalResume resumes;
    @Autowired
    ConnectorActionResultSource results;
    @Autowired
    ConnectorBrowserKeptTabs keptTabs;
    @Autowired
    UserBrowserRepository browserRows;
    JsonNode base;
    long expected;
    /** 채우기 대역이 할 일이다. 실행 원문을 받아 결과를 돌려준다. */
    Function<ConnectorApprovedExecution, CallResult> fill;
    final AtomicInteger fills = new AtomicInteger();


    @BeforeEach
    void checkoutCatalog() throws Exception {
        base = JSON.readTree(Files.readString(Path.of("../test/resources/checkout-snapshot-v1.json"))).get("cases").get(0);
        expected = base.get("executionArgs").get("expectedTotalKrw").longValue();
        doAnswer(invocation -> client.readCatalog().stream().map(CheckoutApprovalTest::checkout).toList()).when(hermes).readCatalog();
        doAnswer(invocation -> {
            ObjectNode prepared = JSON.createObjectNode().put("v", 1);
            prepared.set("executionArgs", base.get("executionArgs"));
            prepared.set("summary", base.get("summary"));
            return CallResult.success(prepared);
        }).when(hermes).prepare(anyString(), anyString(), anyString(), anyString());
        doAnswer(invocation -> {
            fills.incrementAndGet();
            sent = invocation.getArgument(3);
            return fill.apply(sent);
        }).when(hermes).executeApproved(anyString(), anyString(), anyString(), any());
        resetCache();
        limits(100_000, 200_000);
    }


    @AfterEach
    void forgetCatalog() {
        resetCache();
    }


    @Test
    @DisplayName("상한 안의 준비는 PURCHASE 승인 줄 하나와 오늘 남은 한도를 붙인 카드 요약을 만들고 토스 하루 합계에 섞이지 않는다")
    void preparesPurchaseCard() {
        var answer = prepare(UUID.randomUUID().toString());
        assertThat(answer.actionId()).isNotNull();
        var action = action(answer);
        assertThat(action.status()).isEqualTo(ActionStatus.PENDING);
        assertThat(action.risk()).isEqualTo(ToolRisk.PURCHASE);
        var card = summaries.checkoutOf(user, answer.actionId()).orElseThrow();
        assertThat(card.available()).isTrue();
        assertThat(card.expectedTotalKrw()).isEqualTo(expected);
        assertThat(card.remainingTodayKrw()).isEqualTo(200_000L);
        assertThat(summaries.summaryOf(user, answer.actionId())).as("토스 금융 요약은 붙지 않는다").isEmpty();
    }


    @Test
    @DisplayName("상한이 0 이면 카드 없이 「상한을 정해 주세요」 로 거절한다")
    void zeroLimitRejectsWithoutCard() {
        limits(0, 0);
        var answer = prepare(UUID.randomUUID().toString());
        assertThat(answer.actionId()).isNull();
        assertThat(answer.message()).contains("상한을 정해 주세요");
        assertThat(rows()).isZero();
    }


    @Test
    @DisplayName("카드를 만들 때 예상 합계가 한 건 상한과 같으면 지나고 1원 넘으면 카드 없이 걸린 상한을 알린다")
    void cardCreationBoundary() {
        limits(expected - 1, 200_000);
        var over = prepare(UUID.randomUUID().toString());
        assertThat(over.actionId()).isNull();
        assertThat(over.message()).contains("한 건 구매 상한").contains(String.valueOf(expected - 1));
        limits(expected, expected - 1);
        var daily = prepare(UUID.randomUUID().toString());
        assertThat(daily.actionId()).isNull();
        assertThat(daily.message()).contains("하루 구매 상한");
        limits(expected, expected);
        assertThat(prepare(UUID.randomUUID().toString()).actionId()).isNotNull();
    }


    @Test
    @DisplayName("승인 순간, claim 직전, 결과 판정 모두 예상 합계가 한 건과 하루 상한과 같으면 지나 결제를 넘긴다")
    void equalLimitPassesEveryCheck() {
        var answer = prepare(UUID.randomUUID().toString());
        limits(expected, expected);
        fill = claimThen(sheet(expected));
        assertThat(publicApproval.approve(user, answer.actionId(), null, false).status()).isEqualTo(ActionStatus.SUCCEEDED);
        assertThat(onlyHandoff(answer).need()).isEqualTo(HandoffNeed.PAYMENT);
        assertThat(purchaseLimits.view(user).remainingTodayKrw()).isZero();
    }


    @Test
    @DisplayName("결과 판정에서 하루 합계를 읽지 못하면 결제를 넘기지 않고 UNKNOWN 과 그 탭의 탭 확인으로 끝낸다")
    void unreadableSpentEndsUnknown() {
        var first = prepare(UUID.randomUUID().toString());
        fill = claimThen(sheet(expected));
        assertThat(publicApproval.approve(user, first.actionId(), null, false).status()).isEqualTo(ActionStatus.SUCCEEDED);
        var second = prepareOther();
        fill = execution -> {
            claim(execution);
            // 같은 날 앞 줄의 실행 원문을 열 수 없게 만든다. 키를 바꾸거나 본문이 지워진 경우와 같다.
            jdbc.update("update connector_action_execution set execution_args_json = 'x' where action_id = ?", action(first).id());
            return sheet(expected).apply(execution);
        };
        assertThat(publicApproval.approve(user, second.actionId(), null, false).status()).isEqualTo(ActionStatus.UNKNOWN);
        var card = onlyHandoff(second);
        assertThat(card.need()).isEqualTo(HandoffNeed.TAB_CHECK);
        assertThat(card.tab()).isEqualTo(TAB);
    }


    @Test
    @DisplayName("승인 순간에 상한이 낮아졌으면 채우기를 부르지 않고 승인 줄을 FAILED(purchase_limit)로 끝내며 하루 합계에 세지 않는다")
    void approvalMomentLimit() {
        var answer = prepare(UUID.randomUUID().toString());
        limits(expected - 1, 200_000);
        var approved = publicApproval.approve(user, answer.actionId(), null, false);
        assertThat(approved.status()).isEqualTo(ActionStatus.FAILED);
        assertThat(action(answer).errorCode()).isEqualTo(ConnectorCheckoutOutcome.LIMIT);
        assertThat(ConnectorErrorDetail.fromStored(action(answer).resultText()).orElseThrow().code()).isEqualTo("PURCHASE_LIMIT");
        assertThat(fills.get()).isZero();
        assertThat(claims()).isZero();
        assertThat(purchaseLimits.view(user).spentTodayKrw()).isZero();
    }


    @Test
    @DisplayName("주문서의 최종 금액이 예상 합계와 같으면 SUCCEEDED 와 결제 넘기기를 적고 카드에 두 금액을 보이며 하루 합계에 센다")
    void sameAmountHandsOffPayment() {
        var answer = prepare(UUID.randomUUID().toString());
        fill = claimThen(sheet(expected));
        var approved = publicApproval.approve(user, answer.actionId(), null, false);
        assertThat(approved.status()).isEqualTo(ActionStatus.SUCCEEDED);
        HandoffView card = onlyHandoff(answer);
        assertThat(card.need()).isEqualTo(HandoffNeed.PAYMENT);
        assertThat(card.tab()).isEqualTo(TAB);
        assertThat(card.finalAmountKrw()).isEqualTo(expected);
        assertThat(card.expectedTotalKrw()).isEqualTo(expected);
        assertThat(jdbc.queryForObject("select count(*) from notification where user_id = ? and kind = ?", Long.class, user.id(),
            NotificationKind.HANDOFF_REQUESTED.name())).isEqualTo(1L);
        assertThat(purchaseLimits.view(user).spentTodayKrw()).isEqualTo(expected);
        // 같은 승인으로 다시 채우지 않는다.
        assertThatThrownBy(() -> publicApproval.approve(user, answer.actionId(), null, false))
            .isInstanceOf(ApiException.class).extracting("code").isEqualTo(ErrorCode.CONNECTOR_ACTION_NOT_PENDING);
        assertThat(fills.get()).isEqualTo(1);
        assertThatThrownBy(() -> claim(sent)).as("같은 ticket 은 다시 소비하지 않는다").isInstanceOf(ApiException.class);
    }


    @Test
    @DisplayName("최종 금액이 작으면 결제를 넘기고, 1원이라도 크면 넘기지 않고 FAILED(purchase_amount_changed)로 두 금액을 남긴다")
    void largerFinalAmountStops() {
        var lower = prepare(UUID.randomUUID().toString());
        fill = claimThen(sheet(expected - 500));
        assertThat(publicApproval.approve(user, lower.actionId(), null, false).status()).isEqualTo(ActionStatus.SUCCEEDED);
        assertThat(onlyHandoff(lower).finalAmountKrw()).isEqualTo(expected - 500);
        long firstApproved = expected;

        var higher = prepareOther();
        fill = claimThen(sheet(expected + 1));
        assertThat(publicApproval.approve(user, higher.actionId(), null, false).status()).isEqualTo(ActionStatus.FAILED);
        assertThat(action(higher).errorCode()).isEqualTo(ConnectorCheckoutOutcome.AMOUNT_CHANGED);
        var detail = ConnectorErrorDetail.fromStored(action(higher).resultText()).orElseThrow();
        assertThat(detail.details()).containsEntry("final_amount_krw", expected + 1).containsEntry("expected_total_krw", expected);
        assertThat(action(higher).handoff()).isNull();
        assertThat(purchaseLimits.view(user).spentTodayKrw()).as("넘기지 못한 승인은 세지 않는다").isEqualTo(firstApproved);
    }


    @Test
    @DisplayName("claim 뒤 결과 판정 전에 상한을 낮췄으면 결제를 넘기지 않고 FAILED(purchase_limit)다")
    void resultLimit() {
        var answer = prepare(UUID.randomUUID().toString());
        fill = execution -> {
            claim(execution);
            limits(expected - 1, 200_000);
            return sheet(expected).apply(execution);
        };
        assertThat(publicApproval.approve(user, answer.actionId(), null, false).status()).isEqualTo(ActionStatus.FAILED);
        assertThat(action(answer).errorCode()).isEqualTo(ConnectorCheckoutOutcome.LIMIT);
        assertThat(action(answer).handoff()).isNull();
    }


    @Test
    @DisplayName("승인 뒤 채우기 직전에 상한을 낮췄으면 claim 이 거절해 구매하기 전에 끝나고 승인 줄은 FAILED 다")
    void claimLimit() {
        var answer = prepare(UUID.randomUUID().toString());
        fill = execution -> {
            limits(expected - 1, 200_000);
            assertThatThrownBy(() -> claim(execution)).isInstanceOf(ApiException.class);
            return new CallResult(null, ConnectorCallError.FORBIDDEN,
                new ConnectorErrorDetail("NAVER_SHOPPING_CLAIM_REJECTED", Map.of(), null), null);
        };
        assertThat(publicApproval.approve(user, answer.actionId(), null, false).status()).isEqualTo(ActionStatus.FAILED);
        assertThat(contents.findById(action(answer).id()).orElseThrow().consumedAt()).isNull();
        assertThat(action(answer).handoff()).isNull();
    }


    @Test
    @DisplayName("구매하기 뒤 결과를 모르면 다시 보내지 않고 UNKNOWN 과 탭 확인을 넘기며 같은 의도의 새 준비를 막고 하루 합계에 센다")
    void unknownResultHandsOffTabCheck() {
        String toolCallId = UUID.randomUUID().toString();
        var answer = prepare(toolCallId);
        fill = claimThen(execution -> CallResult.success(JSON.createObjectNode().put("status", "UNKNOWN").put("tab", TAB)));
        assertThat(publicApproval.approve(user, answer.actionId(), null, false).status()).isEqualTo(ActionStatus.UNKNOWN);
        var card = onlyHandoff(answer);
        assertThat(card.need()).isEqualTo(HandoffNeed.TAB_CHECK);
        assertThat(card.tab()).isEqualTo(TAB);
        assertThat(card.finalAmountKrw()).isNull();
        assertThat(fills.get()).isEqualTo(1);
        var again = prepare(UUID.randomUUID().toString());
        assertThat(again.actionId()).isNull();
        assertThat(again.message()).isEqualTo(ConnectorFinancialIntake.INTENT_BUSY_MESSAGE.text());
        assertThat(purchaseLimits.view(user).spentTodayKrw()).isEqualTo(expected);
    }


    @Test
    @DisplayName("claim 뒤 응답을 잃으면 UNKNOWN 과 탭 없는 탭 확인이고, claim 전이면 FAILED 이고 넘기지 않는다")
    void lostResponse() {
        var consumed = prepare(UUID.randomUUID().toString());
        fill = execution -> {
            claim(execution);
            throw new IllegalStateException("응답을 잃었다");
        };
        assertThat(publicApproval.approve(user, consumed.actionId(), null, false).status()).isEqualTo(ActionStatus.UNKNOWN);
        var card = onlyHandoff(consumed);
        assertThat(card.need()).isEqualTo(HandoffNeed.TAB_CHECK);
        assertThat(card.tab()).isNull();

        var unsent = prepareOther();
        fill = execution -> {
            throw new IllegalStateException("응답을 잃었다");
        };
        assertThat(publicApproval.approve(user, unsent.actionId(), null, false).status()).isEqualTo(ActionStatus.FAILED);
        assertThat(action(unsent).handoff()).isNull();
    }


    @Test
    @DisplayName("구매하기 전에 사람이 해야 하는 화면을 만나면 FAILED 와 그 단계의 넘기기다. 결제나 탭 확인은 플러그인이 낼 수 없다")
    void humanActionBeforeClaim() {
        var answer = prepare(UUID.randomUUID().toString());
        fill = execution -> CallResult.success(human("login"));
        assertThat(publicApproval.approve(user, answer.actionId(), null, false).status()).isEqualTo(ActionStatus.FAILED);
        assertThat(action(answer).errorCode()).isEqualTo(ConnectorCheckoutOutcome.HUMAN_ACTION);
        assertThat(onlyHandoff(answer).need()).isEqualTo(HandoffNeed.LOGIN);

        var forged = prepareOther();
        fill = execution -> CallResult.success(human("payment"));
        assertThat(publicApproval.approve(user, forged.actionId(), null, false).status())
            .as("claim 없이 온 모르는 성공은 결과를 모르는 것으로 둔다").isEqualTo(ActionStatus.UNKNOWN);
        assertThat(action(forged).handoff().need()).isEqualTo(HandoffNeed.TAB_CHECK);
    }


    @Test
    @DisplayName("구매하기 전에 이어 갈 단계와 함께 멈추면 승인을 끝내지 않고 사람 기다림이고, 하루 합계에 세며 쓰지 않은 ticket 을 거둔다")
    void resumableStopWaits() {
        Instant started = browserRunning();
        var answer = prepare(UUID.randomUUID().toString());
        fill = execution -> CallResult.success(resumable("login"));
        assertThat(publicApproval.approve(user, answer.actionId(), null, false).status()).isEqualTo(ActionStatus.WAITING);
        var action = action(answer);
        assertThat(action.waiting().since()).isEqualTo(clock.instant());
        assertThat(action.waiting().step()).isEqualTo("product");
        assertThat(action.expiresAt()).isEqualTo(clock.instant().plus(ConnectorCheckoutOutcome.WAITING_LIFE));
        assertThat(onlyHandoff(answer).need()).isEqualTo(HandoffNeed.LOGIN);
        assertThat(jdbc.queryForObject("select count(*) from connector_action_execution where action_id = ? and ticket_id is null",
            Long.class, action.id())).isEqualTo(1L);
        assertThat(purchaseLimits.view(user).spentTodayKrw()).isEqualTo(expected);
        assertThat(keptTabs.kept(user.id(), TAB, started)).as("살아 있는 표식의 탭은 닫지 않는다").isTrue();
        // 멈춤은 자동 turn 으로 모델에게 가고, 이어 가기 도구를 부르라는 안내가 붙는다
        var delivered = results.undelivered(action.conversationId());
        assertThat(delivered).singleElement().satisfies(result -> assertThat(result.input())
            .contains("상태: WAITING").contains("approval_resume"));
        // 같은 상품의 새 준비는 기다리는 동안 막는다
        assertThat(prepare(UUID.randomUUID().toString()).actionId()).isNull();
    }


    @Test
    @DisplayName("사람이 그 대화에 메시지를 보낸 뒤의 이어 가기는 새 ticket 과 남긴 탭으로 한 번 더 실행해 주문서까지 가고 claim 은 한 번이다")
    void resumeAfterHumanTurnFillsOnce() {
        browserRunning();
        var answer = prepare(UUID.randomUUID().toString());
        fill = execution -> CallResult.success(resumable("login"));
        publicApproval.approve(user, answer.actionId(), null, false);
        String firstTicket = sent.ticket();
        Long conversation = action(answer).conversationId();
        assertThat(resumes.resume(user, conversation)).as("사람이 아직 메시지를 보내지 않았다").isEqualTo(ConnectorApprovalResume.NOT_YET);
        assertThat(fills.get()).isEqualTo(1);

        handoffs.onTurnStarted(new ConversationTurnStarted(conversation, true));
        assertThat(resumes.resume(user, conversation + 1_000_000)).as("다른 대화는 고르지 않는다").isEqualTo(ConnectorApprovalResume.NONE);
        fill = claimThen(sheet(expected));
        String text = resumes.resume(user, conversation);

        assertThat(fills.get()).isEqualTo(2);
        assertThat(sent.resume()).isEqualTo(new ConnectorApprovedExecution.Resume(TAB, "product"));
        assertThat(sent.ticket()).isNotEqualTo(firstTicket);
        var done = action(answer);
        assertThat(done.status()).isEqualTo(ActionStatus.SUCCEEDED);
        assertThat(done.waiting().resumedAt()).isNotNull();
        assertThat(done.resultDeliveredAt()).as("결과는 도구의 답으로 가고 자동 turn 으로 다시 전하지 않는다").isNotNull();
        assertThat(text).contains("상태: SUCCEEDED");
        assertThat(onlyHandoff(answer).need()).isEqualTo(HandoffNeed.PAYMENT);
        assertThat(claims()).isEqualTo(1L);
        assertThat(purchaseLimits.view(user).spentTodayKrw()).isEqualTo(expected);
        assertThat(resumes.resume(user, conversation)).as("멈출 때마다 한 번이다").isEqualTo(ConnectorApprovalResume.NONE);
    }


    @Test
    @DisplayName("이어 가다 다시 멈추면 다시 사람 기다림이고 처음 멈춘 때부터의 수명은 늘지 않는다")
    void stopAgainKeepsLifetime() {
        browserRunning();
        var answer = prepare(UUID.randomUUID().toString());
        fill = execution -> CallResult.success(resumable("login"));
        publicApproval.approve(user, answer.actionId(), null, false);
        Instant first = action(answer).waiting().since();
        Instant end = action(answer).expiresAt();
        Long conversation = action(answer).conversationId();
        handoffs.onTurnStarted(new ConversationTurnStarted(conversation, true));
        clock.set(clock.instant().plusSeconds(600));
        fill = execution -> CallResult.success(resumable("captcha"));

        resumes.resume(user, conversation);

        var again = action(answer);
        assertThat(again.status()).isEqualTo(ActionStatus.WAITING);
        assertThat(again.waiting().since()).isEqualTo(first);
        assertThat(again.expiresAt()).isEqualTo(end);
        assertThat(onlyHandoff(answer).need()).isEqualTo(HandoffNeed.CAPTCHA);
        assertThat(resumes.resume(user, conversation)).isEqualTo(ConnectorApprovalResume.NOT_YET);
    }


    @Test
    @DisplayName("기다리는 사이 상한을 낮췄으면 이어 가지 않고 FAILED(purchase_limit)로 끝내며 하루 합계에서 뺀다")
    void resumeRechecksLimit() {
        browserRunning();
        var answer = prepare(UUID.randomUUID().toString());
        fill = execution -> CallResult.success(resumable("login"));
        publicApproval.approve(user, answer.actionId(), null, false);
        Long conversation = action(answer).conversationId();
        handoffs.onTurnStarted(new ConversationTurnStarted(conversation, true));
        limits(expected - 1, 200_000);

        String text = resumes.resume(user, conversation);

        assertThat(fills.get()).isEqualTo(1);
        assertThat(action(answer).status()).isEqualTo(ActionStatus.FAILED);
        assertThat(action(answer).errorCode()).isEqualTo(ConnectorCheckoutOutcome.LIMIT);
        assertThat(text).startsWith(ConnectorApprovalResume.ENDED);
        assertThat(purchaseLimits.view(user).spentTodayKrw()).isZero();
    }


    @Test
    @DisplayName("30분이 지났거나 브라우저가 다시 켜졌으면 이어 가지 않고 끝내며, 일정도 같은 줄을 끝내 탭 지키기를 푼다")
    void lifetimeAndBrowserEndWaiting() {
        Instant started = browserRunning();
        var expired = prepare(UUID.randomUUID().toString());
        fill = execution -> CallResult.success(resumable("login"));
        publicApproval.approve(user, expired.actionId(), null, false);
        Long conversation = action(expired).conversationId();
        handoffs.onTurnStarted(new ConversationTurnStarted(conversation, true));
        clock.set(clock.instant().plus(ConnectorCheckoutOutcome.WAITING_LIFE));

        assertThat(resumes.resume(user, conversation)).startsWith(ConnectorApprovalResume.ENDED);
        assertThat(action(expired).status()).isEqualTo(ActionStatus.FAILED);
        assertThat(action(expired).errorCode()).isEqualTo(ConnectorCheckoutOutcome.RESUME_ENDED);
        assertThat(keptTabs.kept(user.id(), TAB, started)).isFalse();
        assertThat(fills.get()).isEqualTo(1);

        var restarted = prepareOther();
        publicApproval.approve(user, restarted.actionId(), null, false);
        assertThat(action(restarted).status()).isEqualTo(ActionStatus.WAITING);
        jdbc.update("update user_browser set started_at = ? where user_id = ?", Timestamp.from(clock.instant().plusSeconds(1)),
            user.id());
        assertThat(resumes.endStale(clock.instant())).isEqualTo(1);
        assertThat(action(restarted).status()).isEqualTo(ActionStatus.FAILED);
        assertThat(purchaseLimits.view(user).spentTodayKrw()).isZero();
    }


    @Test
    @DisplayName("사람이 브라우저를 쥐고 있으면 이어 가지 않고 기다림을 그대로 둔다")
    void heldBrowserDoesNotResume() {
        browserRunning();
        var answer = prepare(UUID.randomUUID().toString());
        fill = execution -> CallResult.success(resumable("login"));
        publicApproval.approve(user, answer.actionId(), null, false);
        Long conversation = action(answer).conversationId();
        handoffs.onTurnStarted(new ConversationTurnStarted(conversation, true));
        jdbc.update("update user_browser set human_held_at = ? where user_id = ?", Timestamp.from(clock.instant()), user.id());

        assertThat(resumes.resume(user, conversation)).isEqualTo(ConnectorApprovalResume.HELD);
        assertThat(action(answer).status()).isEqualTo(ActionStatus.WAITING);
        assertThat(fills.get()).isEqualTo(1);

        // 다른 호출이 이미 이어 가는 중이면 새로 준비하지 말라고 답한다
        jdbc.update("update connector_action set status = 'EXECUTING', resumed_at = ? where id = ?", Timestamp.from(clock.instant()),
            action(answer).id());
        assertThat(resumes.resume(user, conversation)).isEqualTo(ConnectorApprovalResume.RUNNING);
    }


    @Test
    @DisplayName("플러그인 상태를 읽지 못하면 이어 가지 않고 기다림을 그대로 두어 다시 승인하지 않게 한다")
    void unreadableConnectorStateKeepsWaiting() {
        browserRunning();
        var answer = prepare(UUID.randomUUID().toString());
        fill = execution -> CallResult.success(resumable("login"));
        publicApproval.approve(user, answer.actionId(), null, false);
        Long conversation = action(answer).conversationId();
        handoffs.onTurnStarted(new ConversationTurnStarted(conversation, true));
        doThrow(new IllegalStateException("대시보드에 닿지 않는다")).when(hermes).readConnector(anyString(), anyString());

        assertThat(resumes.resume(user, conversation)).isEqualTo(ConnectorApprovalResume.RETRY);
        assertThat(action(answer).status()).isEqualTo(ActionStatus.WAITING);
        assertThat(fills.get()).isEqualTo(1);
    }


    @Test
    @DisplayName("구매 줄은 상시 허락과 대화 허락을 받지 않는다")
    void purchaseTakesNoGrant() {
        var answer = prepare(UUID.randomUUID().toString());
        assertThatThrownBy(() -> publicApproval.approve(user, answer.actionId(), null, true))
            .isInstanceOf(ApiException.class).extracting("code").isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(action(answer).status()).isEqualTo(ActionStatus.PENDING);
    }


    @Test
    @DisplayName("결제 넘기기 맥락을 선언한 읽기 도구는 허용할 때 그 대화의 가장 늦은 결제 넘기기의 시각, 금액, 상품 주소를 받는다")
    void orderCheckReceivesLatestPaymentContext() {
        assertThat(orderCheck(ORDER_CHECK)).as("넘기기 전에는 맥락 없이 허용한다")
            .satisfies(answer -> assertThat(answer.allowed()).isTrue())
            .satisfies(answer -> assertThat(answer.paymentContext()).isNull());
        var first = prepare(UUID.randomUUID().toString());
        fill = claimThen(sheet(expected - 100));
        assertThat(publicApproval.approve(user, first.actionId(), null, false).status()).isEqualTo(ActionStatus.SUCCEEDED);
        var context = orderCheck(ORDER_CHECK).paymentContext();
        assertThat(context).isNotNull();
        assertThat(context.finalAmountKrw()).isEqualTo(expected - 100);
        assertThat(context.expectedTotalKrw()).isEqualTo(expected);
        assertThat(context.url()).isEqualTo(base.get("summary").get("url").stringValue());
        assertThat(context.handedOffAt()).isEqualTo(action(first).handoff().at());

        var second = prepareOther();
        fill = claimThen(sheet(expected));
        assertThat(publicApproval.approve(user, second.actionId(), null, false).status()).isEqualTo(ActionStatus.SUCCEEDED);
        assertThat(orderCheck(ORDER_CHECK).paymentContext().expectedTotalKrw()).as("가장 늦게 넘긴 줄").isEqualTo(13000L);
        assertThat(orderCheck("get_order").paymentContext()).as("선언하지 않은 도구").isNull();
    }


    @Test
    @DisplayName("결제를 넘기지 못한 줄(금액이 커서 FAILED)은 맥락이 되지 않는다")
    void failedCheckoutIsNoPaymentContext() {
        var answer = prepare(UUID.randomUUID().toString());
        fill = claimThen(sheet(expected + 1));
        assertThat(publicApproval.approve(user, answer.actionId(), null, false).status()).isEqualTo(ActionStatus.FAILED);
        var checked = orderCheck(ORDER_CHECK);
        assertThat(checked.allowed()).isTrue();
        assertThat(checked.paymentContext()).isNull();
    }


    private ConnectorPolicyAnswer orderCheck(String tool) {
        resetCache();
        return publicPolicy.decide(profile, session, session, UUID.randomUUID().toString(), "mcp__tossinvest__" + tool, tool, "{}");
    }


    private ConnectorPolicyAnswer prepare(String toolCallId) {
        return publicPolicy.decide(profile, session, session, toolCallId, PREPARE_TOOL, "prepare_trade",
            "{\"v\":1,\"tool\":\"create_order\",\"args\":" + JSON.writeValueAsString(base.get("modelArgs")) + "}");
    }


    /** 수량이 다른 같은 상품이다. 의도 키가 달라 앞 줄에 막히지 않는다. */
    private ConnectorPolicyAnswer prepareOther() {
        ObjectNode model = (ObjectNode)base.get("modelArgs").deepCopy();
        ObjectNode args = (ObjectNode)base.get("executionArgs").deepCopy();
        ObjectNode summary = (ObjectNode)base.get("summary").deepCopy();
        model.put("quantity", 1);
        args.put("quantity", 1).put("expectedTotalKrw", 13000);
        summary.put("quantity", 1).put("itemAmountKrw", 10000).put("expectedTotalKrw", 13000);
        base = JSON.createObjectNode().set("modelArgs", model);
        ((ObjectNode)base).set("executionArgs", args);
        ((ObjectNode)base).set("summary", summary);
        expected = 13000;
        return prepare(UUID.randomUUID().toString());
    }


    private Function<ConnectorApprovedExecution, CallResult> claimThen(Function<ConnectorApprovedExecution, CallResult> result) {
        return execution -> {
            claim(execution);
            return result.apply(execution);
        };
    }


    private void claim(ConnectorApprovedExecution execution) {
        claims.claim(new ConnectorExecutionClaimInput(execution.ticket(), "create_order", execution.argsSha256(), Map.of()));
    }


    private static Function<ConnectorApprovedExecution, CallResult> sheet(long finalAmount) {
        return execution -> CallResult.success(JSON.createObjectNode().put("status", "ORDER_SHEET_READY")
            .put("finalAmountKrw", finalAmount).put("tab", TAB));
    }


    private static ObjectNode human(String need) {
        ObjectNode body = JSON.createObjectNode().put("status", "HUMAN_ACTION_REQUIRED").put("message", "멈췄다");
        body.putObject("handoff").put("code", "NAVER_SHOPPING_LOGIN_REQUIRED").put("need", need).put("tab", TAB);
        return body;
    }


    /** 이어 갈 단계를 함께 낸 사람이 해야 함 결과다. */
    private static ObjectNode resumable(String need) {
        ObjectNode body = human(need);
        body.putObject("resume").put("step", "product");
        return body;
    }


    /** 그 사용자의 브라우저가 지금 켜져 있다. 켠 시각을 돌려준다. */
    private Instant browserRunning() {
        Instant started = clock.instant().minusSeconds(60);
        var browser = UserBrowser.create(user.id(), UserBrowserService.profileKey(user.id()), started);
        browser.beginStart(started);
        browser.markRunning("container-" + user.id(), started);
        browserRows.saveAndFlush(browser);
        return started;
    }


    private HandoffView onlyHandoff(ConnectorPolicyAnswer answer) {
        var open = handoffs.open(user, action(answer).conversationId()).stream()
            .filter(view -> view.handoffId().equals(answer.actionId())).toList();
        assertThat(open).hasSize(1);
        return open.getFirst();
    }


    private void limits(long order, long daily) {
        purchaseLimits.set(user, PurchaseLimitKind.ORDER, order, clock.instant());
        purchaseLimits.set(user, PurchaseLimitKind.DAILY, daily, clock.instant());
    }


    private long rows() {
        return jdbc.queryForObject("select count(*) from connector_action where user_id = ?", Long.class, user.id());
    }


    private long claims() {
        return jdbc.queryForObject("select count(*) from connector_action_execution e join connector_action a on a.id = e.action_id"
            + " where a.user_id = ? and e.consumed_at is not null", Long.class, user.id());
    }


    /** 대역 토스의 선언을 주문서 준비 선언으로 바꾼다. 보호 해시는 연결 상태와 맞도록 그대로 둔다. */
    private static ConnectorManifest checkout(ConnectorManifest manifest) {
        var tools = manifest.tools().stream().map(tool -> switch (tool.name()) {
            case "create_order" -> new ConnectorTool(tool.name(), "PURCHASE", "always", tool.title(), tool.grant(), List.of(),
                tool.description(), tool.argLabels(), tool.summary(), tool.outbound(), tool.conversationGrant(), tool.attendedOnly());
            // 주문 확인 도구 자리다. 결제 넘기기 맥락을 받는다고 선언한다.
            case ORDER_CHECK -> new ConnectorTool(tool.name(), tool.risk(), tool.approval(), tool.title(), tool.grant(),
                tool.identifiers(), tool.description(), tool.argLabels(), tool.summary(), tool.outbound(), tool.conversationGrant(),
                tool.attendedOnly(), true);
            default -> tool;
        })
            .toList();
        ObjectNode guard = JSON.createObjectNode().put("protocol", "approval-claim-v2").put("prepare_tool", "prepare_trade");
        guard.putArray("scope_fields");
        guard.putObject("operations").put("create_order", "CREATE");
        guard.putObject("kinds").put("create_order", "CHECKOUT");
        return new ConnectorManifest(manifest.id(), manifest.title(), manifest.description(), manifest.fields(), manifest.verifyTool(),
            manifest.mcpServer(), manifest.schema(), tools, manifest.skills(), manifest.appearance(), manifest.singleBinding(),
            manifest.ownerBrowser(), manifest.ownerBrowserLoginUrl(), guard, manifest.executionGuardManifestSha256(),
            manifest.snapshot(), manifest.unknownErrors(), manifest.defaultToolPolicy(), manifest.eventWatch());
    }


    /** 정책 판정의 카탈로그 기억을 비워 다음 판정이 바꾼 카탈로그를 읽게 한다. */
    private void resetCache() {
        try {
            var field = cache.getClass().getDeclaredField("readAt");
            field.setAccessible(true);
            field.set(cache, null);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
