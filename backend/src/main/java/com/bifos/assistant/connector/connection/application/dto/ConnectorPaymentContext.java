package com.bifos.assistant.connector.connection.application.dto;

import java.time.Instant;

/**
 * 주문 확인 도구에 줄 결제 넘기기 맥락이다. 도구 판정이 허용으로 답할 때 실어 fos-ctx 가 도구 인자 {@code _fos_payment} 로 넣는다.
 * 모양과 고르는 규칙은 {@code docs/features/connector-policy.md} 의 「도구 호출 판정」 이 갖는다. 서비스 이름을 모른다.
 *
 * @param handedOffAt 그 승인 줄에 결제 넘기기를 적은 시각
 * @param finalAmountKrw 주문서에서 읽은 최종 결제 금액
 * @param expectedTotalKrw 승인한 예상 합계
 * @param url 승인한 상품 주소. Control Plane 은 해석하지 않는다
 */
public record ConnectorPaymentContext(Instant handedOffAt, long finalAmountKrw, long expectedTotalKrw, String url) {
}
