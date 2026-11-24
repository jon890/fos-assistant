package com.bifos.assistant.mcp.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * profile 마다 Hermes 의 Control Plane MCP 연결이 살아 있는지 이 서버가 받은 {@code /mcp} 요청으로 판단한다.
 *
 * <p>Hermes 는 원격 MCP 서버에 기본 180초마다 keepalive 를 보낸다. 이 서버는 {@code ping} 을 모르는 메서드로 답하므로 Hermes 는 그 연결의
 * keepalive 를 {@code tools/list} 로 바꿔 계속 부른다. 연결을 다시 맺을 때도 {@code initialize} 와 {@code tools/list} 를 부른다. 그래서 연결이 살아
 * 있으면 {@link #WINDOW} 안에 그 profile 의 요청이 온다. Hermes 가 재연결을 다 써 연결을 쉬게 두면(parked) 요청이 끊기고, 그동안 모델은 Control Plane
 * 도구를 받지 못한다.
 *
 * <p>기록은 이 프로세스의 메모리에만 둔다. 이 서버는 MCP session 을 두지 않아 Hermes 는 서버가 다시 뜬 것을 모르고 다음 keepalive 를 보낼 뿐이다. 그래서 뜬
 * 뒤 {@link #STARTUP_GRACE} 동안은 기록이 없어도 끊겼는지 알 수 없다({@link #decidedFrom}). 근거와 판정에 쓰는 곳은
 * {@code docs/features/agent-quality/design.md} 의 「평가 환경 오류」 가 갖는다.
 */
@Service
public class McpConnectionLiveness {

    /**
     * 연결이 살아 있다고 보는 마지막 요청의 나이 상한이다. keepalive 기본 180초의 두 배에 여유를 더했다. Hermes 는 도구 호출이 도는 동안 keepalive 를 한 번
     * 건너뛸 수 있어서 간격이 360초까지 벌어질 수 있다.
     */
    public static final Duration WINDOW = Duration.ofMinutes(10);

    /** 서버가 뜬 뒤 살아 있는 연결이라면 요청이 왔을 시간이다. keepalive 기본 180초에 여유를 더했다. */
    public static final Duration STARTUP_GRACE = Duration.ofMinutes(4);

    private final Map<String, Instant> lastSeen = new ConcurrentHashMap<>();
    private final Clock clock;
    private final Instant startedAt;


    public McpConnectionLiveness(Clock clock) {
        this.clock = clock;
        this.startedAt = clock.instant();
    }


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


    /** 이 시각부터는 기록이 없으면 끊긴 것이다. 그 전에는 서버가 막 떠 살아 있는 연결도 아직 요청을 보내지 않았을 수 있다. */
    public Instant decidedFrom() {
        return startedAt.plus(STARTUP_GRACE);
    }
}
