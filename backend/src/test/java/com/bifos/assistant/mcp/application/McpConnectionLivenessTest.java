package com.bifos.assistant.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.testsupport.TestClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** profile 의 Control Plane MCP 연결이 살아 있는지를 받은 요청의 시각으로 판단하는 규칙을 본다. */
class McpConnectionLivenessTest {

    private final TestClock clock = new TestClock();
    private McpConnectionLiveness liveness;


    @BeforeEach
    void setUp() {
        clock.set(Instant.parse("2026-11-24T00:00:00Z"));
        liveness = new McpConnectionLiveness(clock);
    }


    @Test
    @DisplayName("이 서버가 뜬 뒤 요청을 받지 않은 profile 은 연결이 없다")
    void unseenProfileIsNotConnected() {
        assertThat(liveness.connected("evaluation-profile")).isFalse();
        assertThat(liveness.connected(null)).isFalse();
    }


    @Test
    @DisplayName("keepalive 간격 두 번이 지나도 상한 안이면 연결이 살아 있고, 상한을 넘기면 끊긴 것으로 본다")
    void connectionExpiresAfterTheWindow() {
        liveness.seen("evaluation-profile");

        clock.advance(Duration.ofSeconds(360));
        assertThat(liveness.connected("evaluation-profile")).isTrue();

        clock.advance(McpConnectionLiveness.WINDOW);
        assertThat(liveness.connected("evaluation-profile")).isFalse();
    }


    @Test
    @DisplayName("서버가 뜬 뒤 keepalive 한 번이 올 시간이 지나야 기록이 없는 것을 끊긴 것으로 본다")
    void decisionStartsAfterTheStartupGrace() {
        assertThat(liveness.decidedFrom()).isEqualTo(Instant.parse("2026-11-24T00:00:00Z").plus(McpConnectionLiveness.STARTUP_GRACE));
        assertThat(McpConnectionLiveness.STARTUP_GRACE).isGreaterThan(Duration.ofSeconds(180));
    }


    @Test
    @DisplayName("profile 마다 따로 본다. 다른 profile 의 요청은 그 profile 의 연결을 살리지 않는다")
    void profilesAreSeparate() {
        liveness.seen("other-profile");

        assertThat(liveness.connected("other-profile")).isTrue();
        assertThat(liveness.connected("evaluation-profile")).isFalse();
    }
}
