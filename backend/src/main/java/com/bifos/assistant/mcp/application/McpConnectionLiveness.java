package com.bifos.assistant.mcp.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * profile 마다 Hermes 의 Control Plane MCP 연결이 살아 있는지 이 서버가 받은 {@code /mcp} 요청으로 판단한다.
 *
 * <p>Hermes 는 원격 MCP 서버에 기본 180초마다 keepalive 를 보낸다. 이 서버는 {@code ping} 을 모르는 메서드로 답하므로 Hermes 는 그 연결의
 * keepalive 를 {@code tools/list} 로 바꿔 계속 부른다. 연결을 다시 맺을 때도 {@code initialize} 와 {@code tools/list} 를 부른다. 그래서 연결이 살아
 * 있으면 {@link #WINDOW} 안에 그 profile 의 요청이 온다. Hermes 가 재연결을 다 써 연결을 쉬게 두면(parked) 요청이 끊기고, 그동안 모델은 Control Plane
 * 도구를 받지 못한다.
 *
 * <p>기록은 이 프로세스의 메모리에만 둔다. 서버가 다시 뜨면 비어서, 다시 뜬 뒤 Hermes 가 다시 연결하기 전까지는 살아 있지 않다고 본다. 근거와 판정에 쓰는 곳은
 * {@code docs/features/agent-quality/design.md} 의 「평가 환경 오류」 가 갖는다.
 */
@Service
@RequiredArgsConstructor
public class McpConnectionLiveness {

    /**
     * 연결이 살아 있다고 보는 마지막 요청의 나이 상한이다. keepalive 기본 180초의 두 배에 여유를 더했다. Hermes 는 도구 호출이 도는 동안 keepalive 를 한 번
     * 건너뛸 수 있어서 간격이 360초까지 벌어질 수 있다.
     */
    public static final Duration WINDOW = Duration.ofMinutes(10);

    private final Map<String, Instant> lastSeen = new ConcurrentHashMap<>();
    private final Clock clock;


    /** 그 profile 의 토큰으로 인증한 {@code /mcp} 요청을 받았다고 적는다. */
    public void seen(String profileName) {
        lastSeen.put(profileName, clock.instant());
    }


    /** 이 서버가 뜬 뒤 {@link #WINDOW} 안에 그 profile 의 {@code /mcp} 요청을 받았으면 참이다. */
    public boolean connected(String profileName) {
        if (profileName == null) {
            return false;
        }
        Instant seen = lastSeen.get(profileName);
        return seen != null && !seen.isBefore(clock.instant().minus(WINDOW));
    }
}
