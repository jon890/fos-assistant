package com.bifos.assistant.connector.handoff.application.dto;

/**
 * 단추로 넘기기를 닫은 답이다. 웹은 {@link #CONTINUE} 와 {@link #STOPPED} 에서만 단추의 글을 다음 메시지로 보낸다. 뜻은
 * {@code docs/features/mobile-default-chat/design.md} 의 「단추 닫기 API」 가 갖는다.
 */
public enum HandoffCloseResult {
    /** 이 요청이 닫았고 이어 갈 수 있다. */
    CONTINUE,
    /** 이 요청이 닫았고 사람이 그만뒀다. */
    STOPPED,
    /** 이 요청이 닫았지만 남긴 탭이나 연결이 없어 이어 가지 않는다. 대화에 알림 줄을 남겼다. */
    UNAVAILABLE,
    /** 다른 요청이나 플러그인의 다음 호출이 먼저 닫았다. */
    ALREADY_CLOSED
}
