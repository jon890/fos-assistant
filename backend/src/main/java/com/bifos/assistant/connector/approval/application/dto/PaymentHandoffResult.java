package com.bifos.assistant.connector.approval.application.dto;

import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 결제 화면을 사람에게 넘긴 주문서 채우기 줄의 결과 글이다(ADR-20261113 / purchase-checkout). Control Plane 이 정한 JSON 이고
 * 플러그인의 글을 싣지 않는다. 모델은 이 글을 결과로 받고, 넘기기 목록은 결제 카드의 두 금액을 이 글에서 읽는다.
 *
 * @param finalAmountKrw 주문서에서 읽은 최종 결제 금액
 * @param expectedTotalKrw 승인한 예상 합계
 */
public record PaymentHandoffResult(long finalAmountKrw, long expectedTotalKrw) {
    public static final String STATUS = "PAYMENT_HANDOFF";
    static final String MESSAGE = "주문서까지 채웠고 결제 단추는 누르지 않았다. 결제 화면을 사용자에게 넘겼다. "
        + "사용자에게 「내 브라우저」에서 최종 결제 금액을 확인하고 결제해 달라고 알린다. 같은 주문서를 다시 준비하지 않는다.";
    private static final JsonMapper JSON = JsonMapper.builder().build();


    /** 승인 줄의 결과 글로 저장할 JSON 이다. */
    public String toStored() {
        ObjectNode stored = JSON.createObjectNode();
        stored.put("status", STATUS);
        stored.put("message", MESSAGE);
        stored.put("finalAmountKrw", finalAmountKrw);
        stored.put("expectedTotalKrw", expectedTotalKrw);
        return stored.toString();
    }


    /** 저장한 글을 읽는다. 이 모양이 아니면 빈 값이다. */
    public static Optional<PaymentHandoffResult> fromStored(String text) {
        try {
            JsonNode stored = text == null ? null : JSON.readTree(text);
            if (stored == null || !STATUS.equals(stored.path("status").asString(""))
                || !stored.path("finalAmountKrw").isIntegralNumber() || !stored.path("expectedTotalKrw").isIntegralNumber()) {
                return Optional.empty();
            }
            return Optional.of(new PaymentHandoffResult(stored.get("finalAmountKrw").longValue(), stored.get("expectedTotalKrw").longValue()));
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }
}
