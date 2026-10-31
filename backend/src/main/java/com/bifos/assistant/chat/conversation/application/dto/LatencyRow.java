package com.bifos.assistant.chat.conversation.application.dto;

import java.time.LocalDate;

/**
 * 한 날짜와 한 모델 단계의 첫 반응 시간 집계다.
 *
 * @param date {@code Asia/Seoul} 의 날짜
 * @param modelTier 모델 단계 이름. 지난 단계 이름일 수 있다. 단계를 고르지 않은 실행은 null
 * @param turns 답 메시지가 있는 사용자 turn 수
 * @param firstResponse 요청을 받은 때부터 첫 조각을 받기까지
 * @param toSubmit 요청을 받은 때부터 Hermes 에 제출하기까지
 * @param toFirstDelta 제출한 때부터 첫 조각을 받기까지
 */
public record LatencyRow(
    LocalDate date,
    String modelTier,
    long turns,
    LatencyStat firstResponse,
    LatencyStat toSubmit,
    LatencyStat toFirstDelta) {
}
