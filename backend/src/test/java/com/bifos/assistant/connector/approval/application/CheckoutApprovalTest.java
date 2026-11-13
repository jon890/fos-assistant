package com.bifos.assistant.connector.approval.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

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
        assertThat(again.message()).isEqualTo(ConnectorFinancialIntake.INTENT_BUSY_MESSAGE);
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
    @DisplayName("구매 줄은 상시 허락과 대화 허락을 받지 않는다")
    void purchaseTakesNoGrant() {
        var answer = prepare(UUID.randomUUID().toString());
        assertThatThrownBy(() -> publicApproval.approve(user, answer.actionId(), null, true))
            .isInstanceOf(ApiException.class).extracting("code").isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(action(answer).status()).isEqualTo(ActionStatus.PENDING);
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
        var tools = manifest.tools().stream().map(tool -> !"create_order".equals(tool.name()) ? tool
            : new ConnectorTool(tool.name(), "PURCHASE", "always", tool.title(), tool.grant(), List.of(), tool.description(),
                tool.argLabels(), tool.summary(), tool.outbound(), tool.conversationGrant(), tool.attendedOnly()))
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
