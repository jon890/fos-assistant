package com.bifos.assistant.notification.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 웹 푸시를 받는 기기 하나, 곧 브라우저 구독 하나다(ADR-20261120 / web-push).
 *
 * <p>주소와 두 키는 응답과 로그에 내지 않는다. 그래서 {@link #toString()} 은 번호와 이름만 적는다.
 *
 * @param id 기기의 공개 번호. 같은 주소로 다시 등록해도 바뀌지 않는다
 * @param endpoint 푸시 서비스 주소
 * @param endpointHash 주소의 SHA-256 소문자 hex. 다른 사용자 줄에서 같은 주소를 찾고 화면이 「이 기기」 를 고르는 데 쓴다
 * @param p256dh 브라우저의 암호화 공개 키(base64url)
 * @param auth 브라우저의 인증 값(base64url)
 * @param name 사람이 읽는 기기 이름
 * @param timeZone 등록할 때의 브라우저 시간대
 * @param registeredAt 마지막으로 등록한 시각. 다시 등록하면 바뀐다
 */
public record PushDevice(
    UUID id,
    String endpoint,
    String endpointHash,
    String p256dh,
    String auth,
    String name,
    String timeZone,
    Instant registeredAt) {

    public PushDevice {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(endpointHash, "endpointHash");
        Objects.requireNonNull(p256dh, "p256dh");
        Objects.requireNonNull(auth, "auth");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(timeZone, "timeZone");
        Objects.requireNonNull(registeredAt, "registeredAt");
    }


    /** 같은 번호를 두고 키와 이름, 시간대, 등록 시각을 바꾼 기기다. */
    public PushDevice refreshedBy(PushDevice newer) {
        return new PushDevice(
            id, endpoint, endpointHash, newer.p256dh(), newer.auth(), newer.name(), newer.timeZone(), newer.registeredAt());
    }


    @Override
    public String toString() {
        return "PushDevice[id=" + id + ", name=" + name + "]";
    }
}
