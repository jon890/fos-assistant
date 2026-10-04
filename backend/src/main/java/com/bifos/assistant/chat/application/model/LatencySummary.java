package com.bifos.assistant.chat.application.model;

import java.util.List;

/**
 * 최근 며칠의 첫 반응 시간 집계다.
 *
 * @param days 집계한 기간의 일수
 * @param rows 날짜 오름차순이고, 같은 날짜 안에서는 {@code FAST}, {@code BALANCED}, {@code DEEP}, 단계 없음 순서다
 */
public record LatencySummary(int days, List<LatencyRow> rows) {}
