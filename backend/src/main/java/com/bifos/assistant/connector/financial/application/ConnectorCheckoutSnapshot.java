package com.bifos.assistant.connector.financial.application;

import static com.bifos.assistant.connector.financial.application.ConnectorExecutionSnapshot.equal;
import static com.bifos.assistant.connector.financial.application.ConnectorExecutionSnapshot.keys;
import static com.bifos.assistant.connector.financial.application.ConnectorExecutionSnapshot.require;
import static com.bifos.assistant.connector.financial.application.ConnectorExecutionSnapshot.text;

import com.bifos.assistant.shared.util.Sha256;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import tools.jackson.databind.JsonNode;

/**
 * 주문서 준비({@code CHECKOUT})의 표시와 모델·실행 인자를 검증한다(ADR-20261113 / purchase-checkout). 저장과 암호화는 snapshot 이 맡는다.
 *
 * <p>칸과 범위는 {@code docs/adr/ADR-20261113-purchase-checkout.md} 가 가리키는 기능 설계가 갖는다. 대시보드의
 * {@code connector_guard_validation.py} 가 같은 규칙으로 먼저 본다. 서비스 이름을 모르므로 주소는 모양만 본다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class ConnectorCheckoutSnapshot {
    /** summary 의 {@code kind} 값이다. 종류 판정은 이 칸으로 한다. */
    static final String KIND = "CHECKOUT";
    private static final Set<String> INPUT = Set.of("url", "options", "quantity");
    private static final Set<String> EXECUTION = Set.of("checkout", "url", "optionLabels", "quantity", "expectedTotalKrw");
    private static final Set<String> SUMMARY = Set.of("kind", "title", "store", "options", "quantity", "itemAmountKrw",
        "shippingFeeKrw", "expectedTotalKrw", "regionalExtraFee", "url");
    /** 금액은 원 단위 정수이고 10억 원 미만이다. */
    private static final long AMOUNT_BOUND = 1_000_000_000L;
    private static final int OPTIONS_MAX = 3;
    private static final String URL = "https://[a-z0-9.-]{1,253}/[A-Za-z0-9_-]{1,100}/products/[0-9]{1,20}";


    static boolean is(JsonNode summary) {
        return summary.has("kind") && KIND.equals(summary.get("kind").asString(""));
    }


    /** 저장 scope 는 빈 object 다. 쇼핑 연결에는 계좌 같은 공개 칸이 없다. 동작은 만들기 하나다. */
    static void validate(JsonNode input, JsonNode args, JsonNode summary, JsonNode scope, String operation) {
        require("CREATE".equals(operation) && scope.isEmpty());
        keys(input, INPUT);
        keys(args, EXECUTION);
        keys(summary, SUMMARY);
        require(args.get("checkout").isInt() && args.get("checkout").intValue() == 1);
        text(input.get("url"), 1, 2048);
        String url = text(args.get("url"), 1, 400);
        require(url.matches(URL));
        equal(args.get("url"), summary.get("url"));
        int quantity = quantity(args.get("quantity"));
        equal(input.get("quantity"), args.get("quantity"));
        equal(summary.get("quantity"), args.get("quantity"));
        require(quantity >= 1);
        text(summary.get("title"), 1, 200);
        text(summary.get("store"), 1, 50);
        require(summary.get("regionalExtraFee").isBoolean());
        JsonNode wanted = input.get("options");
        JsonNode labels = args.get("optionLabels");
        JsonNode shown = summary.get("options");
        require(wanted.isArray() && labels.isArray() && shown.isArray()
            && labels.size() <= OPTIONS_MAX && wanted.size() == labels.size() && shown.size() == labels.size());
        for (int i = 0; i < labels.size(); i++) {
            text(wanted.get(i), 1, 100);
            text(labels.get(i), 1, 100);
            keys(shown.get(i), Set.of("name", "value"));
            text(shown.get(i).get("name"), 1, 100);
            equal(shown.get(i).get("value"), labels.get(i));
        }
        long item = amount(summary.get("itemAmountKrw"));
        long shipping = amount(summary.get("shippingFeeKrw"));
        long total = amount(summary.get("expectedTotalKrw"));
        require(item > 0 && item + shipping == total);
        equal(args.get("expectedTotalKrw"), summary.get("expectedTotalKrw"));
    }


    /** 실행 원문의 예상 합계다. 검증을 지난 원문만 받는다. 하루 합계와 결과 판정이 쓴다. */
    static long expectedTotal(JsonNode args) {
        return amount(args.get("expectedTotalKrw"));
    }


    /**
     * 같은 상품, 옵션, 수량이면 같은 의도다. 금액은 넣지 않는다. 가격이 바뀌어도 카드가 열려 있으면 새 카드를 만들지 않는다.
     */
    static String requestKey(JsonNode args, Long userId, String connectorId, String tool) {
        StringBuilder body = new StringBuilder(prefixed("fos-checkout-intent-v1"))
            .append(prefixed(userId.toString())).append(prefixed(connectorId)).append(prefixed(tool))
            .append(prefixed(args.get("url").stringValue())).append(prefixed(args.get("quantity").toString()));
        args.get("optionLabels").forEach(label -> body.append(prefixed(label.stringValue())));
        return Sha256.hex(body.toString());
    }


    private static int quantity(JsonNode node) {
        require(node != null && node.isInt() && node.intValue() >= 1 && node.intValue() <= 99);
        return node.intValue();
    }


    private static long amount(JsonNode node) {
        require(node != null && node.isIntegralNumber() && node.canConvertToLong()
            && node.longValue() >= 0 && node.longValue() < AMOUNT_BOUND);
        return node.longValue();
    }


    private static String prefixed(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length + ":" + value;
    }
}
