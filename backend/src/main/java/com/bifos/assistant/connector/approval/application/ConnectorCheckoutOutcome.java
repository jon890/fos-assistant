package com.bifos.assistant.connector.approval.application;

import com.bifos.assistant.connector.action.domain.ActionHandoff;
import com.bifos.assistant.connector.action.domain.ConnectorAction;
import com.bifos.assistant.connector.action.domain.ConnectorActionExecution;
import com.bifos.assistant.connector.action.domain.type.ActionStatus;
import com.bifos.assistant.connector.action.domain.type.HandoffNeed;
import com.bifos.assistant.connector.action.infra.ConnectorActionBodies;
import com.bifos.assistant.connector.approval.application.dto.PaymentHandoffResult;
import com.bifos.assistant.hermes.application.dto.CallResult;
import com.bifos.assistant.hermes.application.dto.ConnectorErrorDetail;
import com.bifos.assistant.hermes.application.dto.ConnectorRecovery;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import tools.jackson.databind.JsonNode;

/**
 * 주문서 채우기({@code CHECKOUT})의 결과를 판정해 {@code EXECUTING} 승인 줄을 끝내고 사람에게 넘길 화면을 적는다(ADR-20261113 / purchase-checkout).
 *
 * <p>판정 표는 {@code docs/adr/ADR-20261113-purchase-checkout.md} 가 가리키는 기능 설계가 갖는다. 부르는 쪽은 사용자, 승인 줄,
 * 실행 줄 순서로 이미 잠갔어야 한다. 플러그인 결과는 정확한 키와 타입으로 읽고 어긋나면 결과를 모르는 것으로 본다. 저장하는 글은
 * Control Plane 이 정한 JSON 이고 플러그인의 글을 싣지 않는다. 결과를 모르면 다시 보내지 않고 탭 확인을 넘긴다(spec DECISION33).
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class ConnectorCheckoutOutcome {
    /** 주문서의 최종 결제 금액이 승인한 예상 합계보다 크다. */
    static final String AMOUNT_CHANGED = "purchase_amount_changed";
    /** 구매하기 전에 사람이 해야 하는 화면을 만났다. 넘기기는 그 단계다. */
    static final String HUMAN_ACTION = "human_action_required";
    /** 사람 기다림이 수명이나 브라우저 꺼짐으로 끝났거나 이어 가기 전에 연결이 바뀌었다. 이어 가지 않았다. */
    static final String RESUME_ENDED = "resume_ended";
    /** 이어 가기 표식의 수명이다. 처음 멈춘 때부터 센다(browser-cowork spec DECISION11). */
    static final Duration WAITING_LIFE = Duration.ofMinutes(30);
    private static final String TAB = "[A-Za-z0-9]{1,128}";
    private static final String STEP = "[a-z][a-z0-9_]{0,31}";
    private static final String CODE = "[A-Z][A-Z0-9_]{0,63}";
    private static final long AMOUNT_BOUND = 1_000_000_000L;


    /**
     * 채우기의 결과로 줄을 끝낸다.
     *
     * @param result 실행 결과. 응답을 잃었으면 null
     * @param expectedTotalKrw 승인한 예상 합계
     * @return 사람에게 화면을 넘겼으면 그 단계
     */
    static Optional<HandoffNeed> settle(ConnectorAction action, ConnectorActionExecution row, CallResult result, long expectedTotalKrw,
        ConnectorActionBodies bodies, Instant now) {
        if (action.status() != ActionStatus.EXECUTING) {
            throw new IllegalStateException("connector action must be EXECUTING but is " + action.status());
        }
        if (result == null || !result.ok()) {
            ConnectorFinancialOutcome.settle(action, row, result, bodies, now);
            return action.status() == ActionStatus.UNKNOWN ? handOff(action, HandoffNeed.TAB_CHECK, null, now) : Optional.empty();
        }
        JsonNode body = result.result();
        if (row.consumedAt() == null) {
            // claim 을 소비하지 않았으면 구매하기를 누르지 않았다. 사람이 해야 하는 화면에서 멈춘 결과만 실패로 끝낸다.
            JsonNode handoff = body == null ? null : body.get("handoff");
            Optional<HandoffNeed> need = humanAction(body);
            if (need.isEmpty()) {
                // claim 없이 온 그 밖의 성공은 계약과 어긋나 보냈는지 가릴 수 없다. 금융 줄과 같이 결과를 모르는 것으로 둔다.
                action.unknown(now);
                return handOff(action, HandoffNeed.TAB_CHECK, tabOf(body), now);
            }
            Optional<String> step = resumeStep(body);
            if (step.isPresent()) {
                // 구매하기 전에 멈췄다. 승인을 끝내지 않고 사람을 기다린다(browser-cowork spec REQ8). 쓰지 않은 권한은 거두고 이어 갈 때 새로 낸다.
                row.releaseTicket();
                action.awaitHuman(step.get(), WAITING_LIFE, now);
                return handOff(action, need.get(), handoff.get("tab").stringValue(), now);
            }
            bodies.fail(action, HUMAN_ACTION, new ConnectorErrorDetail(handoff.get("code").stringValue(), Map.of(), null).toStored(), now);
            return handOff(action, need.get(), handoff.get("tab").stringValue(), now);
        }
        if (!sheetReady(body)) {
            action.unknown(now);
            return handOff(action, HandoffNeed.TAB_CHECK, tabOf(body), now);
        }
        long finalAmount = body.get("finalAmountKrw").longValue();
        if (finalAmount > expectedTotalKrw) {
            // 승인보다 1원이라도 크면 넘기지 않는다(spec DECISION10). 플러그인이 같은 판정으로 주문서 탭을 닫는다.
            var details = new LinkedHashMap<String, Object>();
            details.put("final_amount_krw", finalAmount);
            details.put("expected_total_krw", expectedTotalKrw);
            bodies.fail(action, AMOUNT_CHANGED,
                new ConnectorErrorDetail("PURCHASE_AMOUNT_CHANGED", details, ConnectorRecovery.RECHECK).toStored(), now);
            return Optional.empty();
        }
        bodies.succeed(action, new PaymentHandoffResult(finalAmount, expectedTotalKrw).toStored(), now);
        return handOff(action, HandoffNeed.PAYMENT, body.get("tab").stringValue(), now);
    }


    private static Optional<HandoffNeed> handOff(ConnectorAction action, HandoffNeed need, String tab, Instant now) {
        action.handOff(ActionHandoff.open(need, tab, now));
        return Optional.of(need);
    }


    /** {@code {status: "ORDER_SHEET_READY", finalAmountKrw, tab}} 정확히 이 모양인가. */
    private static boolean sheetReady(JsonNode body) {
        return body != null && body.isObject() && body.propertyNames().equals(Set.of("status", "finalAmountKrw", "tab"))
            && "ORDER_SHEET_READY".equals(body.get("status").asString(""))
            && body.get("finalAmountKrw").isIntegralNumber() && body.get("finalAmountKrw").canConvertToLong()
            && body.get("finalAmountKrw").longValue() >= 0 && body.get("finalAmountKrw").longValue() < AMOUNT_BOUND
            && body.get("tab").isString() && body.get("tab").stringValue().matches(TAB);
    }


    /** PR2 의 사람이 해야 함 결과다. 결제와 탭 확인은 플러그인이 낼 수 없다({@link HandoffNeed#fromWire}). */
    private static Optional<HandoffNeed> humanAction(JsonNode body) {
        if (body == null || !body.isObject() || !"HUMAN_ACTION_REQUIRED".equals(body.path("status").asString(""))) {
            return Optional.empty();
        }
        JsonNode handoff = body.get("handoff");
        if (handoff == null || !handoff.isObject() || !handoff.propertyNames().equals(Set.of("code", "need", "tab"))
            || !handoff.get("code").isString() || !handoff.get("code").stringValue().matches(CODE)
            || !handoff.get("tab").isString() || !handoff.get("tab").stringValue().matches(TAB)
            || !handoff.get("need").isString()) {
            return Optional.empty();
        }
        return HandoffNeed.fromWire(handoff.get("need").stringValue());
    }


    /** 플러그인이 넘기기와 함께 낸 이어 갈 단계 {@code resume: {step}} 이다. 정확히 이 모양이 아니면 이어 가지 않는다. */
    private static Optional<String> resumeStep(JsonNode body) {
        JsonNode resume = body.get("resume");
        if (resume == null || !resume.isObject() || !resume.propertyNames().equals(Set.of("step")) || !resume.get("step").isString()
            || !resume.get("step").stringValue().matches(STEP)) {
            return Optional.empty();
        }
        return Optional.of(resume.get("step").stringValue());
    }


    /** 사람 기다림을 이어 가지 않고 끝낸다. */
    static void endWaiting(ConnectorAction action, ConnectorActionBodies bodies, Instant now) {
        bodies.fail(action, RESUME_ENDED, new ConnectorErrorDetail("RESUME_ENDED", Map.of(), null).toStored(), now);
    }


    /** 모르는 결과에서도 플러그인이 남긴 탭(바깥 {@code tab} 이나 {@code handoff.tab})을 읽을 수 있으면 카드가 그 탭을 연다. 없으면 null 이다. */
    private static String tabOf(JsonNode body) {
        if (body == null || !body.isObject()) {
            return null;
        }
        for (JsonNode tab : new JsonNode[] {body.get("tab"), body.path("handoff").get("tab")}) {
            if (tab != null && tab.isString() && tab.stringValue().matches(TAB)) {
                return tab.stringValue();
            }
        }
        return null;
    }
}
