package com.bifos.assistant.hermes.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.validation.annotation.Validated;

/**
 * @param profileKeyDir profile 마다 mode 600 파일 하나가 들어 있는 디렉터리. 파일 이름이 profile
 * 이름이고 내용이 그 profile 의 {@code API_SERVER_KEY} 다
 * @param dashboardBaseUrl profile 관리 API 를 가진 Hermes 대시보드 주소. 기본값을 두지 않아 값이
 * 없으면 기동할 때 드러난다
 * @param dashboardToken 그 대시보드에 {@code Authorization: Bearer} 로 보낼 값. 기본값을 두지
 * 않는다
 * @param sharedListenerBaseUrl 모든 profile 을 {@code /p/<profile>/...} 로 받는 listener 의 주소.
 * 첫 로그인에 만드는 에이전트의 {@code apiBaseUrl} 이 이 값에 profile 접두를 붙인 것이다.
 * 우리 환경의 값이라 이 저장소가 정하지 않고 설정으로 받는다
 * @param pollInterval 실행 상태를 다시 묻기까지 기다리는 시간
 * @param runTimeout 사람이 답을 기다리는 실행 하나가 끝나기를 기다려 주는 한도
 * @param connectTimeout Hermes 호출 한 번의 TCP 연결 한도
 * @param readTimeout Hermes 호출 한 번의 응답 한도. 제출과 조회가 모두 곧바로 돌아오므로 {@code
 *     runTimeout} 보다 훨씬 짧다
 * @param backgroundRunTimeout 맡긴 자식 실행과 예약 작업이 연 turn 처럼 사람이 그 자리에서 기다리지 않는 실행의 한도. 비우면 20분이다.
 * {@code runTimeout} 보다 짧게 주면 {@code runTimeout} 으로 올린다. 어느 실행이 어느 한도를 쓰는지는
 * {@code docs/features/execution.md} 의 「실행 한도」 가 갖는다
 */
@Validated
@ConfigurationProperties(prefix = "hermes")
public record HermesProperties(
    String profileKeyDir,
    String dashboardBaseUrl,
    String dashboardToken,
    String sharedListenerBaseUrl,
    Duration pollInterval,
    Duration runTimeout,
    Duration connectTimeout,
    Duration readTimeout,
    Duration backgroundRunTimeout) {


    /**
     * 사람이 기다리지 않는 실행의 기본 한도다. 2026-11-24 운영에서 커리어 담당의 공고 추천(공고 모으기 78초와 모델 왕복 약 21번)이 5분을
     * 넘겨 끊겼다. 그 일이 두세 번 들어가는 여유를 두되, 끝나지 않는 실행이 사용자 자리를 오래 쥐지 않게 한다.
     */
    private static final Duration DEFAULT_BACKGROUND_RUN_TIMEOUT = Duration.ofMinutes(20);

    @ConstructorBinding
    public HermesProperties {
        pollInterval = pollInterval == null ? Duration.ofMillis(700) : pollInterval;
        runTimeout = runTimeout == null ? Duration.ofMinutes(5) : runTimeout;
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(5) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(30) : readTimeout;
        backgroundRunTimeout = backgroundRunTimeout == null ? DEFAULT_BACKGROUND_RUN_TIMEOUT : backgroundRunTimeout;
        // 긴 한도가 사람이 기다리는 실행의 한도보다 짧으면 맡긴 일이 대화보다 먼저 끊긴다
        backgroundRunTimeout = backgroundRunTimeout.compareTo(runTimeout) < 0 ? runTimeout : backgroundRunTimeout;
    }


    /** 긴 한도를 기본값으로 두는 생성자다. 테스트가 쓴다. */
    public HermesProperties(
        String profileKeyDir,
        String dashboardBaseUrl,
        String dashboardToken,
        String sharedListenerBaseUrl,
        Duration pollInterval,
        Duration runTimeout,
        Duration connectTimeout,
        Duration readTimeout) {
        this(
            profileKeyDir,
            dashboardBaseUrl,
            dashboardToken,
            sharedListenerBaseUrl,
            pollInterval,
            runTimeout,
            connectTimeout,
            readTimeout,
            null);
    }


    /**
     * 공유 listener 로 그 profile 을 부를 주소를 만든다.
     *
     * <p>접두를 붙이는 규칙은 {@code hermes/docs/hermes-contract.md} 의 「profile 접두」가 갖는다. 끝의
     * {@code /} 를 떼고 붙이므로 {@code //p/} 가 되지 않는다.
     */
    public String profileBaseUrl(String profileName) {
        String base = sharedListenerBaseUrl.endsWith("/")
            ? sharedListenerBaseUrl.substring(0, sharedListenerBaseUrl.length() - 1)
            : sharedListenerBaseUrl;
        return base + "/p/" + profileName;
    }
}
