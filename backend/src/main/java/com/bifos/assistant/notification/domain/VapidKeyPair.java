package com.bifos.assistant.notification.domain;

import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;

/**
 * 웹 푸시가 서명에 쓰는 VAPID 키 쌍과 연락처다(ADR-20261120 / web-push).
 *
 * @param publicKey P-256 공개 키
 * @param privateKey P-256 비밀 키
 * @param publicKeyBase64Url 공개 키의 비압축 점 65바이트를 base64url 로 쓴 것. 화면이 구독을 만들 때 쓴다
 * @param subject 푸시 서비스가 연락할 곳. {@code mailto:} 나 {@code https:} 주소
 */
public record VapidKeyPair(ECPublicKey publicKey, ECPrivateKey privateKey, String publicKeyBase64Url, String subject) {

    @Override
    public String toString() {
        return "VapidKeyPair[subject=" + subject + "]";
    }
}
