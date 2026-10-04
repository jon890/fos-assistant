package com.bifos.assistant.chat.application.model;

/**
 * 새로 연 전달 묶음과 그 첫 시도의 번호다(ADR-070).
 *
 * @param deliveryId 전달 묶음 번호
 * @param attemptId 첫 시도 번호
 */
public record ResultDeliveryStart(Long deliveryId, Long attemptId) {}
