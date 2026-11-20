package com.bifos.assistant.notification.application.dto;

/**
 * 화면이 구독을 만들 때 읽는 서버의 웹 푸시 설정이다(ADR-20261120 / web-push).
 *
 * @param enabled 서버에 VAPID 키가 있어 푸시를 보낼 수 있는가
 * @param publicKey VAPID 공개 키(base64url). 꺼졌으면 null
 */
public record PushConfig(boolean enabled, String publicKey) {
}
