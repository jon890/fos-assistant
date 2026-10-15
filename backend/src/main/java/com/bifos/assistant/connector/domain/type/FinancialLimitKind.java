package com.bifos.assistant.connector.domain.type;

import java.util.Arrays;
import java.util.Optional;

/**
 * 금융 주문 상한의 종류다. 운영 최대치 다섯 값과 하나씩 맞는다(ADR-20261015 / financial-user-limits).
 *
 * <p>{@code apiName} 은 MCP 도구와 화면 API 가 쓰는 이름이고, 저장은 enum 이름으로 한다.
 */
public enum FinancialLimitKind {
    ORDER_AMOUNT_KRW("order_amount_krw", "KRW", "한 건 원화 금액"),
    ORDER_AMOUNT_USD("order_amount_usd", "USD", "한 건 달러 금액"),
    ORDER_QUANTITY("order_quantity", null, "한 건 수량"),
    DAILY_AMOUNT_KRW("daily_amount_krw", "KRW", "하루 원화 합계"),
    DAILY_AMOUNT_USD("daily_amount_usd", "USD", "하루 달러 합계");

    private final String apiName;
    private final String currency;
    private final String label;

    FinancialLimitKind(String apiName, String currency, String label) {
        this.apiName = apiName;
        this.currency = currency;
        this.label = label;
    }

    public String apiName() {
        return apiName;
    }

    /** 금액 상한의 통화다. 수량 상한은 null 이다. */
    public String currency() {
        return currency;
    }

    /** 에이전트와 화면에 보이는 한국어 이름이다. */
    public String label() {
        return label;
    }

    /** API 이름으로 찾는다. 모르는 이름이면 빈 값이다. */
    public static Optional<FinancialLimitKind> fromApiName(String name) {
        return Arrays.stream(values()).filter(kind -> kind.apiName.equals(name)).findFirst();
    }

    /** 그 통화의 한 건 금액 상한이다. 통화는 KRW 나 USD 다. */
    public static FinancialLimitKind orderAmount(String currency) {
        return "KRW".equals(currency) ? ORDER_AMOUNT_KRW : ORDER_AMOUNT_USD;
    }

    /** 그 통화의 하루 합계 상한이다. 통화는 KRW 나 USD 다. */
    public static FinancialLimitKind dailyAmount(String currency) {
        return "KRW".equals(currency) ? DAILY_AMOUNT_KRW : DAILY_AMOUNT_USD;
    }
}
