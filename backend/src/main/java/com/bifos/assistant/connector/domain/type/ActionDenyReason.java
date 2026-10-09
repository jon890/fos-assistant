package com.bifos.assistant.connector.domain.type;

/**
 * 커넥터 도구 호출을 거절한 까닭이다(ADR-049). DB 에 이름 그대로 저장되므로 값을 바꾸면 마이그레이션이 필요하다.
 *
 * <p>어느 조건을 어떤 순서로 보는지는 {@code ToolPolicyDecision.decide} 가 갖는다.
 */
public enum ActionDenyReason {
    /** 카탈로그를 읽지 못했거나 카탈로그에 그 커넥터가 없어 정책을 알 수 없다 */
    POLICY_UNAVAILABLE,
    /** 연결이나 그 바인딩이 {@code READY} 가 아니다 */
    NOT_READY,
    /** 그 커넥터의 MCP 서버가 낸 도구가 아니거나, 도구를 선언하는 manifest 에 그 도구의 선언이 없다 */
    UNDECLARED,
    /** 위험도가 {@code DESTRUCTIVE} 나 {@code FINANCIAL} 이다. 이 위험도는 아직 열지 않았다 */
    RISK_NOT_OPEN,
    /** 인자 글이 승인 줄에 원문으로 둘 수 있는 상한({@code ToolPolicyDecision.MAX_ARGS_BYTES})을 넘는다 */
    ARGS_TOO_LARGE,
    /** 먼저 살펴보기 트리 안의 호출인데 위험도가 {@code READ} 이고 승인 방식이 {@code none} 인 도구가 아니다(ADR-080) */
    READ_ONLY_RUN
}
