package com.bifos.assistant.notification.presentation;

import com.bifos.assistant.notification.application.dto.PushRegistration;
import com.bifos.assistant.notification.domain.PushDevice;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 웹 푸시 경로의 요청과 응답이다(ADR-20261120 / web-push). 응답에 주소와 키 원문을 넣지 않는다. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class PushDtos {

    /**
     * 화면이 구독을 만들 때 읽는 서버 설정이다.
     *
     * @param enabled 서버에 VAPID 키가 있어 푸시를 보낼 수 있는가
     * @param publicKey VAPID 공개 키(base64url). 꺼졌으면 null
     */
    public record PushConfigView(boolean enabled, String publicKey) {
    }

    /**
     * 내 기기 한 줄이다.
     *
     * @param id 기기의 공개 번호
     * @param endpointHash 주소의 SHA-256 소문자 hex. 화면이 자기 구독 주소의 해시와 비교해 「이 기기」 를 고른다
     */
    public record PushDeviceView(UUID id, String name, Instant registeredAt, String endpointHash) {

        public static PushDeviceView from(PushDevice device) {
            return new PushDeviceView(device.id(), device.name(), device.registeredAt(), device.endpointHash());
        }
    }

    /** 브라우저 구독의 두 키다. */
    public record PushKeys(String p256dh, String auth) {

        @Override
        public String toString() {
            return "PushKeys[]";
        }
    }

    /** 등록 요청이다. */
    public record RegisterPushDeviceRequest(String endpoint, PushKeys keys, String name, String timeZone) {

        public PushRegistration toRegistration() {
            return new PushRegistration(
                endpoint, keys == null ? null : keys.p256dh(), keys == null ? null : keys.auth(), name, timeZone);
        }


        @Override
        public String toString() {
            return "RegisterPushDeviceRequest[name=" + name + "]";
        }
    }

    /** 이 브라우저의 기기를 빼는 요청이다. */
    public record UnregisterCurrentRequest(String endpoint) {

        @Override
        public String toString() {
            return "UnregisterCurrentRequest[]";
        }
    }
}
