package com.bifos.assistant.notification.application.dto;

import java.time.Instant;

/**
 * 웹 푸시 VAPID 키의 상태다. 비밀 키는 담지 않는다.
 *
 * @param set 키가 있어 푸시가 켜졌는가
 * @param publicKey 공개 키(base64url). 비밀이 아니라 원문이다. 꺼졌으면 null
 * @param subject 연락처. 꺼졌으면 null
 * @param updatedBy 마지막으로 바꾼 사용자 번호. 줄이 없으면 null
 * @param updatedAt 마지막으로 바꾼 시각. 줄이 없으면 null
 * @param deviceCount 지금 등록된 기기의 수. 확인 창이 「등록된 기기 N대」 로 보인다
 */
public record VapidStatus(boolean set, String publicKey, String subject, Long updatedBy, Instant updatedAt, int deviceCount) {
}
