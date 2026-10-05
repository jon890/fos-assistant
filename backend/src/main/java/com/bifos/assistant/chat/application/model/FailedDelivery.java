package com.bifos.assistant.chat.application.model;

import java.time.Instant;

/**
 * {@code FAILED} 로 남은 결과 전달 묶음 하나다. 먼저 알리기의 실패 카드가 읽는다.
 *
 * @param deliveryId 묶음 번호
 * @param conversationId 묶음이 속한 대화 번호
 * @param attemptCount 지금까지 만든 시도 수. 마지막 시도의 번호와 같다
 * @param updatedAt 묶음의 상태가 바뀐 시각
 */
public record FailedDelivery(Long deliveryId, Long conversationId, int attemptCount, Instant updatedAt) {}
