package com.bifos.assistant.notification.application.dto;

/**
 * 브라우저가 보낸 웹 푸시 구독이다(ADR-20261120 / web-push). 주소와 두 키는 로그에 내지 않는다.
 *
 * @param endpoint 푸시 서비스 주소
 * @param p256dh 브라우저의 암호화 공개 키(base64url)
 * @param auth 브라우저의 인증 값(base64url)
 * @param name 화면이 브라우저와 OS 로 만든 기기 이름
 * @param timeZone 브라우저 시간대
 */
public record PushRegistration(String endpoint, String p256dh, String auth, String name, String timeZone) {

    @Override
    public String toString() {
        return "PushRegistration[name=" + name + ", timeZone=" + timeZone + "]";
    }
}
