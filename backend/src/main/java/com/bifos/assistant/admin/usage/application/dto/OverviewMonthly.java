package com.bifos.assistant.admin.usage.application.dto;

/**
 * 관리자 요약의 「이번 달 사용량」 카드가 보이는 그룹 전체의 이번 달 합계다.
 *
 * @param month {@code 2026-11} 꼴의 달
 * @param totalExecutions 그 달의 실행 수. 「사용량과 비용」 요약 탭과 같이 가격이 잡힌 실행과 못 잡은 실행의 합이다
 * @param subscriptionExecutions 그중 구독 경로로 돈 실행 수
 * @param actualCostMicros 예상 추가 사용 요금. 통화 단위의 100만분의 1
 * @param incomplete 가격을 찾지 못한 실행, 금액을 확인하지 못한 도우미, 도우미를 관측하지 못한 실행 가운데 하나라도 있으면 참.
 * 금액이 실제보다 적을 수 있다는 뜻이다
 */
public record OverviewMonthly(
    String month, long totalExecutions, long subscriptionExecutions, long actualCostMicros, boolean incomplete) {
}
