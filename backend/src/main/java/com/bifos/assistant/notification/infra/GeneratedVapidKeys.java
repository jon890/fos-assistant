package com.bifos.assistant.notification.infra;

/**
 * 새로 만든 VAPID 키 쌍이다.
 *
 * @param sealed 서버 비밀값에 쓸 글. 비밀 키가 들어 있다
 * @param publicKey 공개 키(base64url)
 */
public record GeneratedVapidKeys(String sealed, String publicKey) {

    @Override
    public String toString() {
        return "GeneratedVapidKeys[]";
    }
}
