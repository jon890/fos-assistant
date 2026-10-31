package com.bifos.assistant.chat.conversation.application.dto;

import java.util.List;

/**
 * 최근 며칠의 첫 반응 시간 집계다.
 *
 * @param days 집계한 기간의 일수
 * @param rows 날짜 오름차순이고, 같은 날짜 안에서는 지금 칸의 선언 순서, 지난 단계 이름, 단계 없음 순서다
 */
public record LatencySummary(int days, List<LatencyRow> rows) {
}
