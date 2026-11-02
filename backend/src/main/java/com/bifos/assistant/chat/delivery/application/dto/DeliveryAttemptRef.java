package com.bifos.assistant.chat.delivery.application.dto;

/**
 * 전달 묶음의 시도 하나를 가리킨다(ADR-075). 묶음은 마지막 시도만 남기므로 시도를 고치는 쓰기는 이 번호가 아직 마지막
 * 시도일 때만 바꾼다.
 *
 * @param deliveryId 전달 묶음 번호
 * @param attemptNo 1 부터 세는 시도 번호. 그 시도를 만들 때의 묶음 {@code attempt_count} 다
 */
public record DeliveryAttemptRef(Long deliveryId, int attemptNo) {
}
