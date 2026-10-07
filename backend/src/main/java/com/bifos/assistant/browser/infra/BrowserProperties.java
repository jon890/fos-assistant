package com.bifos.assistant.browser.infra;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 사용자 브라우저의 설정이다({@code docs/backend/user-browser.md} 의 「설정」).
 *
 * <p>이미지, 망, 자원, 프로필 루트는 브라우저 proxy 의 정책이 강제한다. Control Plane 은 그 정책과 같은 값을 받아 생성 요청에 싣는다.
 * 꺼져 있으면 동시 수와 시간 말고는 비어 있어도 기동한다. 켜져 있으면 비어 있는 값이 있을 때 기동을 멈춘다.
 *
 * @param enabled 꺼져 있으면 상태 조회 말고 모든 쓰기가 {@code BROWSER_DISABLED} 다
 * @param proxyUrl 브라우저 proxy 의 주소
 * @param image 컨테이너 이미지
 * @param network 컨테이너를 붙이는 망 이름. 그 망의 주소로 CDP 에 닿는다
 * @param cdpPort 컨테이너 안에서 CDP 를 받는 포트
 * @param profileRoot Control Plane 이 보는 프로필 루트. 사용자마다 그 아래에 디렉터리 하나를 둔다
 * @param profileHostRoot 같은 루트를 Docker 호스트에서 본 경로. 생성 요청의 {@code Binds} 에 쓴다
 * @param profileMount 컨테이너 안에서 프로필 디렉터리를 붙이는 경로. 이미지가 쓰는 경로와 같아야 한다
 * @param memoryMb 컨테이너 메모리 상한(MB). 스왑은 주지 않는다
 * @param cpu 컨테이너가 쓰는 CPU 수
 * @param pidsLimit 컨테이너 안의 프로세스 수 상한
 * @param shmMb 컨테이너의 {@code /dev/shm} 크기(MB)
 * @param maxRunning 동시에 켜 둘 수 있는 브라우저 수. {@code STARTING} 과 {@code RUNNING} 을 센다
 * @param idleTimeout 화면도 중계 연결도 없이 이만큼 지나면 자동으로 멈춘다
 * @param startTimeout 켠 뒤 CDP 가 답하기를 기다리는 시간
 * @param screenTimeout 로그인 화면 하나가 열려 있을 수 있는 시간. 넘으면 화면을 닫는다
 */
@Validated
@ConfigurationProperties(prefix = "assistant.browser")
public record BrowserProperties(
        boolean enabled,
        String proxyUrl,
        String image,
        String network,
        Integer cdpPort,
        String profileRoot,
        String profileHostRoot,
        String profileMount,
        int memoryMb,
        Double cpu,
        Integer pidsLimit,
        Integer shmMb,
        int maxRunning,
        Duration idleTimeout,
        Duration startTimeout,
        Duration screenTimeout) {

    private static final String PREFIX = "assistant.browser.";

    /** 동시 수와 시간은 늘 확인한다. 나머지는 켜져 있을 때만 확인한다. 상한은 두지 않는다. 운영이 정한다. */
    public BrowserProperties {
        requireAtLeastOne("max-running", maxRunning);
        requirePositive("idle-timeout", idleTimeout);
        requirePositive("start-timeout", startTimeout);
        requirePositive("screen-timeout", screenTimeout);
        if (enabled) {
            requireText("proxy-url", proxyUrl);
            requireText("image", image);
            requireText("network", network);
            requireText("profile-root", profileRoot);
            requireText("profile-host-root", profileHostRoot);
            requireText("profile-mount", profileMount);
            requireAtLeastOne("cdp-port", cdpPort);
            requireAtLeastOne("memory-mb", memoryMb);
            requireAtLeastOne("pids-limit", pidsLimit);
            requireAtLeastOne("shm-mb", shmMb);
            if (cpu == null || cpu <= 0) {
                throw new IllegalStateException(PREFIX + "cpu must be positive: " + cpu);
            }
        }
    }

    private static void requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(PREFIX + name + " is required when the browser is enabled");
        }
    }

    private static void requireAtLeastOne(String name, Integer value) {
        if (value == null || value < 1) {
            throw new IllegalStateException(PREFIX + name + " must be at least 1: " + value);
        }
    }

    private static void requirePositive(String name, Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalStateException(PREFIX + name + " must be positive: " + value);
        }
    }
}
