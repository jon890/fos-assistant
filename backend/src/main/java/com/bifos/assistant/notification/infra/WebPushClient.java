package com.bifos.assistant.notification.infra;

/**
 * 암호화한 웹 푸시 본문을 푸시 서비스에 보낸다(ADR-20261120 / web-push).
 *
 * <p>주소가 허용한 푸시 서비스인지는 부르는 쪽이 먼저 본다. 검사는 이 인터페이스를 네트워크 없이 받은 요청을 모으는 대역으로 바꾼다.
 */
public interface WebPushClient {

    /**
     * @param endpoint 푸시 서비스 주소
     * @param authorization VAPID {@code Authorization} 머리 값
     * @param body RFC 8291 {@code aes128gcm} 본문
     */
    PushResponse send(String endpoint, String authorization, byte[] body);
}
