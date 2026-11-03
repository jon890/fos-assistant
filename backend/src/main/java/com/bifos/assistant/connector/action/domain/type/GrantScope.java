package com.bifos.assistant.connector.action.domain.type;

/**
 * 승인 줄이 승인하면서 함께 준 허락의 범위다(ADR-20261102 / table-reduce-r2). 한 승인은 둘 가운데 하나만 준다.
 *
 * <p>저장하는 글자 그대로다. 값을 바꾸면 마이그레이션과 {@code data-schema.md} 를 함께 고친다.
 */
public enum GrantScope {
    /** 그 사용자의 그 플러그인 그 도구에 통하는 상시 허락이다(ADR-050). */
    TOOL,
    /** 그 대화의 그 에이전트 실행에서만 그 도구에 통하는 대화 허락이다(ADR-20261012 / conversation-grant). */
    CONVERSATION
}
