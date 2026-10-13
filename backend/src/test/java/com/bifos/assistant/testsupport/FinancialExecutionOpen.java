package com.bifos.assistant.testsupport;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 금융 실행 관문의 상한을 연다. 운영 기본값은 닫힘이라 금융 준비, 승인, claim 을 끝까지 도는 검사에 단다.
 * 상한은 대역 주문을 넉넉히 담는다. {@link BackendIntegrationTest} 와 함께 단다.
 *
 * <p>허용 목록은 비워 둔다. 항목이 계좌와 그 주인 메일이라, 검사가 주인 사용자를 만든 뒤
 * {@link OverridableLiveProperties#during} 로 {@code 7:<그 메일>} 같은 항목을 넣는다.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@OverrideProperties({
    "assistant.connector.financial.max-order-amount-krw=1000000",
    "assistant.connector.financial.max-order-amount-usd=10000",
    "assistant.connector.financial.max-order-quantity=1000",
    "assistant.connector.financial.max-daily-amount-krw=10000000",
    "assistant.connector.financial.max-daily-amount-usd=100000"})
public @interface FinancialExecutionOpen {
}
