package com.bifos.assistant.connector.connection.application.dto;

import java.util.UUID;

/**
 * 도구 호출 판정이 hook 에 돌려줄 답이다(ADR-049).
 *
 * @param allowed hook 이 그 호출을 통과시켜도 되는가
 * @param message 막을 때 모델에게 보일 글. 통과이면 빈 글이다. 내부 이름과 예외 본문을 넣지 않는다
 * @param actionId 승인 요청 번호. 승인 요청을 만들지 않았으면 null
 * @param paymentContext 허용한 호출에 hook 이 인자로 넣을 결제 넘기기 맥락. 도구가 {@code payment_context} 를 선언하지 않았거나
 * 찾지 못했으면 null
 */
public record ConnectorPolicyAnswer(boolean allowed, String message, UUID actionId, ConnectorPaymentContext paymentContext) {

    /** 결제 넘기기 맥락이 없는 답이다. */
    public ConnectorPolicyAnswer(boolean allowed, String message, UUID actionId) {
        this(allowed, message, actionId, null);
    }


    /** 같은 답에 결제 넘기기 맥락을 싣는다. 허용이 아닌 답에는 싣지 않는다. */
    public ConnectorPolicyAnswer withPaymentContext(ConnectorPaymentContext context) {
        return allowed ? new ConnectorPolicyAnswer(true, message, actionId, context) : this;
    }
}
