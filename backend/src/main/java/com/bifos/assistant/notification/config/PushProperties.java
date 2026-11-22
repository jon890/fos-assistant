package com.bifos.assistant.notification.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * 웹 푸시의 설정이다(ADR-20261120 / web-push).
 *
 * <p>VAPID 키와 연락처는 설정이 아니라 서버 비밀값 {@code vapid} 에 둔다(ADR-20261120 / server-secret). 여기에는 보내기의 모양만 둔다.
 *
 * @param allowedEndpointHosts 등록받을 푸시 서비스 호스트. {@code .} 으로 시작하면 그 접미사로 끝나는 호스트다. {@code .google.com} 처럼 푸시 서비스가 아닌 호스트까지 덮는 넓은 접미사는 넣지 않는다
 * @param connectTimeout 푸시 서비스에 연결하는 제한 시간
 * @param requestTimeout 푸시 요청 하나의 응답 제한 시간
 * @param retryDelays 일시 실패 뒤 다시 보내기 전의 기다림. 개수가 다시 보내는 횟수다
 * @param retryAfterMax 푸시 서비스가 {@code Retry-After} 로 기다리라고 해도 이보다 오래 기다리지 않는다
 * @param maxDevices 사용자 한 명의 기기 수 상한
 */
@Validated
@ConfigurationProperties(prefix = "assistant.push")
public record PushProperties(
    @DefaultValue( {
        "fcm.googleapis.com",
        "jmt17.google.com",
        "updates.push.services.mozilla.com",
        "web.push.apple.com",
        ".notify.windows.com"}) List<String> allowedEndpointHosts,
    @DefaultValue("5s") Duration connectTimeout,
    @DefaultValue("10s") Duration requestTimeout,
    @DefaultValue({"1s", "4s"}) List<Duration> retryDelays,
    @DefaultValue("30s") Duration retryAfterMax,
    @DefaultValue("10") int maxDevices){

    public PushProperties {
        allowedEndpointHosts = allowedEndpointHosts == null ? List.of() : List.copyOf(allowedEndpointHosts);
        retryDelays = retryDelays == null ? List.of() : List.copyOf(retryDelays);
        if (maxDevices < 1) {
            throw new IllegalArgumentException("assistant.push.max-devices must be at least 1");
        }
    }
}
