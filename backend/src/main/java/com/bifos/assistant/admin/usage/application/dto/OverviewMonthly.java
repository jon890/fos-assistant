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
 * @param evaluation 평가 전용 사용자의 이번 달 합계. 위 칸에서 빠져 따로 센다. 평가 사용자가 없으면 null
 */
public record OverviewMonthly(
    String month,
    long totalExecutions,
    long subscriptionExecutions,
    long actualCostMicros,
    boolean incomplete,
    Evaluation evaluation) {

    /**
     * 배포 뒤 확인과 평가가 쓴 실행 수와 예상 추가 사용 요금이다(docs/features/agent-quality/spec.md 의 REQ2).
     *
     * @param incomplete 그룹 합계의 같은 칸과 같은 뜻이다. 평가 실행만 센다
     */
    public record Evaluation(long executions, long actualCostMicros, boolean incomplete) {
    }
}
