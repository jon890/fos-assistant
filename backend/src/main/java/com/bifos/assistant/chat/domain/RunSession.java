package com.bifos.assistant.chat.domain;

import java.util.UUID;

/**
 * 실행 하나가 쓰는 session 두 칸이다.
 *
 * <p>두 칸이 모두 문자열이라 순서가 바뀌어도 컴파일되는 문제를 없애려고 값 하나로 묶었다.
 *
 * <ul>
 *   <li>{@code runtimeSessionId}: Hermes 에 보낼 session. 대화가 압축으로 교체되면 바뀐다
 *   <li>{@code correlationSessionId}: 실행 줄의 {@code hermes_session_id} 에 적을 session. MCP 호출이 들고 오는
 *       서명한 루트 session 으로 부모 실행을 찾는 값이라 압축 교체와 무관하게 대화의 루트 session 을 가리킨다
 *       (ADR-031, ADR-032)
 * </ul>
 */
public record RunSession(String runtimeSessionId, String correlationSessionId) {

    private static final String PREFIX = "fos-";

    /** 흐름의 하위 실행처럼 부모의 session 을 잇지 않는 실행이 쓴다. 두 칸이 같은 새 session 이다. */
    public static RunSession fresh() {
        String sessionId = newSessionId();
        return new RunSession(sessionId, sessionId);
    }

    /**
     * 대화의 turn 이 쓰는 session 이다.
     *
     * <p>루트가 있으면 실행 줄에 루트를 적고, 루트가 없는 옛 대화는 보내는 session 을 그대로 적는다.
     */
    public static RunSession ofConversation(String hermesSessionId, String hermesRootSessionId) {
        return new RunSession(hermesSessionId, hermesRootSessionId != null ? hermesRootSessionId : hermesSessionId);
    }

    /** Control Plane 이 정하는 session 은 모두 {@code fos-<uuid>} 형태다. */
    public static String newSessionId() {
        return PREFIX + UUID.randomUUID();
    }
}
