package com.bifos.assistant.chat.application.model;

import com.bifos.assistant.model.domain.type.ModelTier;
import java.time.LocalDate;

/**
 * 한 날짜와 한 모델 단계의 첫 반응 시간 집계다.
 *
 * @param date {@code Asia/Seoul} 의 날짜
 * @param modelTier 모델 단계. 단계를 고르지 않은 실행은 null
 * @param turns 답 메시지가 있는 사용자 turn 수
 * @param firstResponse 요청을 받은 때부터 첫 조각을 받기까지
 * @param toSubmit 요청을 받은 때부터 Hermes 에 제출하기까지
 * @param toFirstDelta 제출한 때부터 첫 조각을 받기까지
 */
public record LatencyRow(
        LocalDate date,
        ModelTier modelTier,
        long turns,
        LatencyStat firstResponse,
        LatencyStat toSubmit,
        LatencyStat toFirstDelta) {}
