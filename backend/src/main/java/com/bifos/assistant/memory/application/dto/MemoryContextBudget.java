package com.bifos.assistant.memory.application.dto;

/**
 * 한 그룹의 기억 조립 예산이다(docs/features/memory/spec.md 의 REQ4). 글자 수로 센다.
 *
 * <p>범위 검사는 관리자가 저장할 때만 한다({@code MemoryContextSettings}). 이 값 자체는 0 이상이면 받는다. 회수 측정이 요약 구역을 끈
 * 모드(요약 예산 0)와 짧은 상한을 쓰는 테스트가 같은 조립기로 돌게 하려는 것이다.
 *
 * @param summaryMaxChars 요약 구역 예산. 머리 줄과 구분 줄까지 센다
 * @param maxChars 기억 전체 상한
 */
public record MemoryContextBudget(long summaryMaxChars, long maxChars) {

    /** 설정 줄이 없는 그룹의 요약 구역 예산이다. */
    public static final long DEFAULT_SUMMARY_MAX_CHARS = 4_000;

    /** 설정 줄이 없는 그룹의 기억 전체 상한이다. */
    public static final long DEFAULT_MAX_CHARS = 10_000;

    /** 설정 줄이 없는 그룹의 예산이다. */
    public static final MemoryContextBudget DEFAULTS = new MemoryContextBudget(DEFAULT_SUMMARY_MAX_CHARS, DEFAULT_MAX_CHARS);


    public MemoryContextBudget {
        if (summaryMaxChars < 0 || maxChars < 0) {
            throw new IllegalArgumentException("a memory context budget is not negative");
        }
    }
}
