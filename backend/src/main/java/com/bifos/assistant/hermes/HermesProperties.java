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
 * @param sharedListenerBaseUrl 모든 profile 을 {@code /p/<profile>/...} 로 받는 listener 의 주소.
 *     첫 로그인에 만드는 에이전트의 {@code apiBaseUrl} 이 이 값에 profile 접두를 붙인 것이다.
 *     우리 환경의 값이라 이 저장소가 정하지 않고 설정으로 받는다
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
        String sharedListenerBaseUrl,
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

    /**
     * 공유 listener 로 그 profile 을 부를 주소를 만든다.
     *
     * <p>접두를 붙이는 규칙은 {@code docs/hermes-integration.md} 의 「profile 접두」가 갖는다. 끝의
     * {@code /} 를 떼고 붙이므로 {@code //p/} 가 되지 않는다.
     */
    public String profileBaseUrl(String profileName) {
        String base = sharedListenerBaseUrl.endsWith("/")
                ? sharedListenerBaseUrl.substring(0, sharedListenerBaseUrl.length() - 1)
                : sharedListenerBaseUrl;
        return base + "/p/" + profileName;
    }
}
