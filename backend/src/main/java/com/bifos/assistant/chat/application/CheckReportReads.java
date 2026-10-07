package com.bifos.assistant.chat.application;

import java.time.Instant;

/**
 * 사용자가 점검 대화를 열었거나 그 대화에 메시지를 보냈다고 알리는 port 다.
 *
 * <p>{@code chat} 은 보고를 모른다. {@code proactive} 가 구현해 그 대화에서 그 사용자의 열지 않은 보고를 연 것으로 적는다. 지금 화면의
 * 「보고 열기」 를 누르지 않고 점검 대화를 직접 읽은 사용자의 매일 깨우기가 열지 않은 보고로 계속 건너뛰지 않게 하기 위해서다.
 */
public interface CheckReportReads {

    /**
     * @param userId 대화의 주인. 부르는 쪽이 주인을 확인했다
     * @param conversationId {@code purpose = CHECK} 인 대화
     */
    void markRead(Long userId, Long conversationId, Instant now);
}
