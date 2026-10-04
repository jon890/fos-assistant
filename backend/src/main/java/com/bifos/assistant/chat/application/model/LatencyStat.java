package com.bifos.assistant.chat.application.model;

/**
 * 지표 하나의 건수와 백분위다.
 *
 * <p>값이 없는 지표는 {@code count} 가 0 이고 두 백분위가 null 이다. 0 으로 채우지 않는다.
 *
 * @param count 그 지표의 시각이 둘 다 있는 turn 수
 * @param p50Ms 중앙값. 밀리초
 * @param p90Ms 90번째 백분위. 밀리초
 */
public record LatencyStat(long count, Long p50Ms, Long p90Ms) {}
