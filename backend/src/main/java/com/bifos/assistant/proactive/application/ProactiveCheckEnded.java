package com.bifos.assistant.proactive.application;

/**
 * 먼저 살펴보기 하나가 끝났다는 사건이다(ADR-077). 어떻게 끝났든 그 트리의 위임 결과를 전했다고 적은 뒤 낸다.
 *
 * <p>{@code orchestration} 이 받아 그 트리의 도는 위임 자식을 멈춘다. {@code proactive} 는 {@code orchestration} 을 부르지
 * 않는다.
 *
 * @param rootExecutionId 살펴보기 turn 의 실행 줄. 그 트리의 루트다
 */
public record ProactiveCheckEnded(Long rootExecutionId) {}
