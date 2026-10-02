package com.bifos.assistant.usage.application;

/**
 * 축별 합계의 한 줄이다.
 *
 * @param cost native 자식의 토큰과 금액이 더해진 축 레코드
 * @param subagents 그 줄에 더한 자식 수. 실행 수에는 들지 않는다
 */
public record BreakdownLine<T>(T cost, long subagents) {}
