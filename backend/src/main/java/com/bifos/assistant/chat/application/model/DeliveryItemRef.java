package com.bifos.assistant.chat.application.model;

/**
 * 전달 묶음에 넣을 결과 하나의 이름이다(ADR-070).
 *
 * @param source 결과를 낸 쪽의 이름
 * @param resultKey 그쪽이 쓰는 결과 이름
 */
public record DeliveryItemRef(String source, String resultKey) {}
