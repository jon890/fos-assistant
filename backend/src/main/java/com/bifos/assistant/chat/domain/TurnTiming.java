package com.bifos.assistant.chat.domain;

import com.bifos.assistant.model.domain.type.ModelTier;
import java.time.Instant;

/**
 * 사용자 turn 루트 실행의 네 시각 가운데 첫 반응 시간 집계가 쓰는 값이다.
 *
 * <p>실행 줄을 통째로 읽지 않고 필요한 칸만 읽으려고 조회가 곧바로 이 모양으로 채운다. 비어 있는 시각은 그대로 null 이다.
 *
 * @param executionId 루트 실행 번호
 * @param requestReceivedAt 요청을 받은 시각
 * @param submittedAt Hermes 에 제출하기 직전 시각
 * @param firstDeltaAt 첫 assistant delta 를 받은 시각
 * @param modelTier 그 실행의 모델 단계. 단계를 고르지 않은 실행은 null
 */
public record TurnTiming(
        Long executionId, Instant requestReceivedAt, Instant submittedAt, Instant firstDeltaAt, ModelTier modelTier) {}
