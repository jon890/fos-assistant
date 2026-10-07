package com.bifos.assistant.usage.application;

/**
 * 한 달치 실행과 native 자식을 함께 합친 금액과 완전성 건수다.
 *
 * <p>자식은 실행 건수에 세지 않고 따로 센다. 금액을 확인하지 못한 자식이 있으면 합계가 실제보다 작다는
 * 뜻이고, 그 수를 셋으로 나눠 낸다. 근거는 ADR-062 에 있다.
 *
 * @param estimatedMicros 환산 금액의 합. 금액이 있는 자식을 포함한다
 * @param actualMicros 실제 청구액의 합. 금액이 있는 자식을 포함한다
 * @param pricedExecutions 환산 금액이 잡힌 실행 수
 * @param unpricedExecutions 가격을 찾지 못한 실행 수
 * @param subscriptionExecutions 구독 경로로 돈 실행 수
 * @param pricedSubagents 금액이 합계에 든 자식 수
 * @param pendingSubagents 아직 사용량을 확인하는 중인 자식 수
 * @param unconfirmedSubagents 사용량을 끝내 확인하지 못한 자식 수
 * @param unpricedSubagents 사용량은 적었지만 금액을 내지 못한 자식 수
 */
public record MonthlyUsageSummary(
        long estimatedMicros,
        long actualMicros,
        long pricedExecutions,
        long unpricedExecutions,
        long subscriptionExecutions,
        long pricedSubagents,
        long pendingSubagents,
        long unconfirmedSubagents,
        long unpricedSubagents,
        long observationIncompleteExecutions) {}
