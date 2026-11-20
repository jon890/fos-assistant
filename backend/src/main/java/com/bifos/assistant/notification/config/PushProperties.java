package com.bifos.assistant.notification.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * 웹 푸시의 설정이다(ADR-20261120 / web-push).
 *
 * <p>공개 키, 비밀 키 파일, 연락처는 함께 채우거나 함께 비운다. 비우면 푸시가 꺼진 채 기동하고 보내기를 건너뛴다. 파일을 읽어 키로
 * 해석하는 일은 {@code VapidKeys} 가 한다. 값은 환경 변수로 받고 저장소에 적지 않는다.
 *
 * @param vapidPublicKey VAPID 공개 키. P-256 비압축 점 65바이트의 base64url
 * @param vapidPrivateKeyFile VAPID 비밀 키 파일의 경로. 파일에는 32바이트 비밀 값의 base64url 한 줄이 있다
 * @param subject 푸시 서비스가 연락할 곳. {@code mailto:} 나 {@code https:} 주소
 * @param allowedEndpointHosts 등록받을 푸시 서비스 호스트. {@code .} 으로 시작하면 그 접미사로 끝나는 호스트다
 * @param connectTimeout 푸시 서비스에 연결하는 제한 시간
 * @param requestTimeout 푸시 요청 하나의 응답 제한 시간
 * @param retryDelays 일시 실패 뒤 다시 보내기 전의 기다림. 개수가 다시 보내는 횟수다
 * @param retryAfterMax 푸시 서비스가 {@code Retry-After} 로 기다리라고 해도 이보다 오래 기다리지 않는다
 * @param maxDevices 사용자 한 명의 기기 수 상한
 */
@Validated
@ConfigurationProperties(prefix = "assistant.push")
public record PushProperties(
    String vapidPublicKey,
    String vapidPrivateKeyFile,
    String subject,
    @DefaultValue( {
        "fcm.googleapis.com",
        "updates.push.services.mozilla.com",
        "web.push.apple.com",
        ".notify.windows.com"}) List<String> allowedEndpointHosts,
    @DefaultValue("5s") Duration connectTimeout,
    @DefaultValue("10s") Duration requestTimeout,
    @DefaultValue({"1s", "4s"}) List<Duration> retryDelays,
    @DefaultValue("30s") Duration retryAfterMax,
    @DefaultValue("10") int maxDevices){

    public PushProperties {
        vapidPublicKey = strip(vapidPublicKey);
        vapidPrivateKeyFile = strip(vapidPrivateKeyFile);
        subject = strip(subject);
        allowedEndpointHosts = allowedEndpointHosts == null ? List.of() : List.copyOf(allowedEndpointHosts);
        retryDelays = retryDelays == null ? List.of() : List.copyOf(retryDelays);
        boolean anySet = !vapidPublicKey.isEmpty() || !vapidPrivateKeyFile.isEmpty() || !subject.isEmpty();
        boolean allSet = !vapidPublicKey.isEmpty() && !vapidPrivateKeyFile.isEmpty() && !subject.isEmpty();
        if (anySet && !allSet) {
            throw new IllegalArgumentException(
                "assistant.push: vapid-public-key, vapid-private-key-file, subject 는 함께 채우거나 함께 비워야 한다");
        }
        if (maxDevices < 1) {
            throw new IllegalArgumentException("assistant.push.max-devices must be at least 1");
        }
    }


    /** 키 셋이 채워져 있는가. 파일을 읽을 수 있는지는 보지 않는다. */
    public boolean configured() {
        return !vapidPublicKey.isEmpty();
    }


    private static String strip(String value) {
        return value == null ? "" : value.strip();
    }
}
