package com.bifos.assistant.mcp.application.dto;

/**
 * 예약 작업 도구가 받은 칸이다. 만들기는 이름, 지시, 시각 하나가 차 있고, 고치기는 받은 칸만 차 있다. 빈 칸은 null 이다.
 *
 * @param cron 표준 5필드 cron
 * @param fireAt {@code 2026-11-21T07:00} 꼴의 시간대 없는 시각
 * @param notifyPolicy {@code always}, {@code on_failure}, {@code never}
 */
public record TaskToolInput(String title, String instruction, String cron, String fireAt, String notifyPolicy) {

    /** 고칠 칸이 하나도 없는가. */
    public boolean empty() {
        return title == null && instruction == null && cron == null && fireAt == null && notifyPolicy == null;
    }
}
