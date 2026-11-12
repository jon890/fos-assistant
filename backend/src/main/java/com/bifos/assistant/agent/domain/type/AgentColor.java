package com.bifos.assistant.agent.domain.type;

/**
 * 에이전트 이름표의 색이다(docs/features/agent-team/spec.md 의 DECISION3).
 *
 * <p>화면의 {@code web/src/lib/agent-badge.ts} 가 같은 이름을 테마 색으로 바꾼다. 이름을 더하거나 바꾸면 그 파일과
 * {@code test/repo/agent-badge.test.ts} 도 함께 바꾼다.
 */
public enum AgentColor {
    VIOLET,
    BLUE,
    TEAL,
    GREEN,
    AMBER,
    ORANGE,
    ROSE,
    SLATE
}
