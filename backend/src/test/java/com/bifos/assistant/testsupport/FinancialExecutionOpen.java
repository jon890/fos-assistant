package com.bifos.assistant.testsupport;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 금융 실행 관문을 연다. 운영 기본값은 닫힘이라 금융 준비, 승인, claim 을 끝까지 도는 검사에 단다.
 * 허용 계좌는 그 검사들이 쓰는 대역 계좌이고, 상한은 대역 주문을 넉넉히 담는다. {@link BackendIntegrationTest} 와 함께 단다.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@OverrideProperties({
    "assistant.connector.financial.enabled=true",
    "assistant.connector.financial.allowed-accounts=7,000007",
    "assistant.connector.financial.max-order-amount-krw=1000000",
    "assistant.connector.financial.max-order-amount-usd=10000",
    "assistant.connector.financial.max-order-quantity=1000",
    "assistant.connector.financial.max-daily-amount-krw=10000000",
    "assistant.connector.financial.max-daily-amount-usd=100000"})
public @interface FinancialExecutionOpen {
}
