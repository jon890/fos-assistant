package com.bifos.assistant.proactive.check.application.dto;

import java.time.Instant;

/**
 * 사건 실행 하나가 답을 남기고 끝났다는 사건이다(ADR-20261105 / event-watch). 예외나 멈춤으로 끝난 실행은 내지 않는다. 그 실패는 점검 대화의
 * 알림 줄이 알린다.
 *
 * <p>{@code task} 가 받아 알림을 만들거나 조용한 시간이면 미룬다. {@code proactive} 는 바인딩과 알림을 모른다.
 *
 * @param userId 살펴보기를 연 사용자
 * @param conversationId 답을 남긴 점검 대화
 * @param batch 그 실행이 살펴본 새 항목. {@code sourceId} 가 원천을 가리킨다
 * @param notifyCount 「새로 알릴 것」 으로 남은 발견 가운데 {@code topicKey} 가 입력 식별자인 것의 서로 다른 수. 입력 밖 식별자는 세지 않는다
 * @param endedAt 끝난 시각. 조용한 시간을 이 시각으로 가린다
 */
public record EventCheckEnded(Long userId, Long conversationId, EventBatch batch, int notifyCount, Instant endedAt) {
}
