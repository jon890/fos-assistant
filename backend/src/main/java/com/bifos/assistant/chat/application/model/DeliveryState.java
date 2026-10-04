package com.bifos.assistant.chat.application.model;

import com.bifos.assistant.chat.domain.type.DeliveryStatus;

/**
 * 화면이 알림 줄 아래에 그릴 전달 묶음의 상태다(ADR-070).
 *
 * @param deliveryId 전달 묶음 번호. 다시 전달할 때 이 번호로 부른다
 * @param status 묶음의 상태
 */
public record DeliveryState(Long deliveryId, DeliveryStatus status) {}
