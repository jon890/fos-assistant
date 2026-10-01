package com.bifos.assistant.connector.domain.type;

/**
 * 커넥터 도구 호출을 거절한 까닭이다(ADR-047). DB 에 이름 그대로 저장되므로 값을 바꾸면 마이그레이션이 필요하다.
 *
 * <p>뜻은 {@code docs/connectors.md} 의 「도구 호출 판정」 표가 갖는다.
 */
public enum ActionDenyReason {
    POLICY_UNAVAILABLE,
    NOT_READY,
    UNDECLARED,
    RISK_NOT_OPEN,
    ARGS_TOO_LARGE
}
