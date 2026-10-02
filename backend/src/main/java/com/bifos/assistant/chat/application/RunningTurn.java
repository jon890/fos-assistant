package com.bifos.assistant.chat.application;

import java.time.Instant;

/**
 * 대화에 지금 도는 turn 이다. 같은 대화를 연 다른 창이 답이 오는 중인지 알 때 쓴다.
 *
 * @param running 도는 turn 이 있다
 * @param executionId 그 turn 의 루트 실행 번호. 돌지 않거나 아직 번호가 붙기 전이면 null
 * @param startedAt 그 실행 줄이 시작한 시각. 번호가 없거나 줄을 찾지 못하면 null
 */
public record RunningTurn(boolean running, Long executionId, Instant startedAt) {}
