package com.bifos.assistant.hermes;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param profileKeyDir profile 마다 mode 600 파일 하나가 들어 있는 디렉터리. 파일 이름이 profile
 *     이름이고 내용이 그 profile 의 {@code API_SERVER_KEY} 다
 * @param dashboardBaseUrl profile 관리 API 를 가진 Hermes 대시보드 주소. 기본값을 두지 않아 값이
 *     없으면 기동할 때 드러난다
 * @param dashboardToken 그 대시보드에 {@code Authorization: Bearer} 로 보낼 값. 기본값을 두지
 *     않는다
 * @param pollInterval 실행 상태를 다시 묻기까지 기다리는 시간
 * @param runTimeout 실행 하나가 끝나기를 기다려 주는 한도
 * @param connectTimeout Hermes 호출 한 번의 TCP 연결 한도
 * @param readTimeout Hermes 호출 한 번의 응답 한도. 제출과 조회가 모두 곧바로 돌아오므로 {@code
 *     runTimeout} 보다 훨씬 짧다
 */
@ConfigurationProperties(prefix = "hermes")
public record HermesProperties(
        String profileKeyDir,
        String dashboardBaseUrl,
        String dashboardToken,
        Duration pollInterval,
        Duration runTimeout,
        Duration connectTimeout,
        Duration readTimeout) {

    public HermesProperties {
        pollInterval = pollInterval == null ? Duration.ofMillis(700) : pollInterval;
        runTimeout = runTimeout == null ? Duration.ofMinutes(5) : runTimeout;
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(5) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(30) : readTimeout;
    }
}
