package com.bifos.assistant.quality.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bifos.assistant.mcp.application.McpConnectionLiveness;
import com.bifos.assistant.testsupport.TestClock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 문항의 시작과 끝에서 평가 비서의 Control Plane MCP 연결을 보는 규칙을 본다. 서버가 막 뜬 동안의 판정이 핵심이다. */
class ControlPlaneConnectionTest {

    private static final String PROFILE = "evaluation-profile";
    private static final Instant BOOT = Instant.parse("2026-11-24T00:00:00Z");

    private final TestClock clock = new TestClock();
    private final TurnDriver driver = mock(TurnDriver.class);
    private McpConnectionLiveness liveness;
    private ControlPlaneConnection connection;


    @BeforeEach
    void setUp() {
        clock.set(BOOT);
        liveness = new McpConnectionLiveness(clock);
        connection = new ControlPlaneConnection(liveness, driver, clock);
        // 기다림은 마감까지 조건을 한 번 더 보는 것으로 대신한다.
        when(driver.waitUntil(any(), any())).thenAnswer(call -> ((BooleanSupplier)call.getArgument(1)).getAsBoolean());
    }


    @Test
    @DisplayName("서버가 막 떠 요청이 아직 없으면 시작에서는 살아 있다고 보고, 끝에서는 알 수 있는 때까지 기다린다")
    void waitsForTheFirstKeepaliveAfterBoot() {
        clock.advance(Duration.ofSeconds(30));

        assertThat(connection.atStart(PROFILE)).isTrue();
        assertThat(connection.atEnd(PROFILE)).isFalse();

        ArgumentCaptor<Instant> deadline = ArgumentCaptor.forClass(Instant.class);
        verify(driver).waitUntil(deadline.capture(), any());
        assertThat(deadline.getValue()).isEqualTo(BOOT.plus(McpConnectionLiveness.STARTUP_GRACE));
    }


    @Test
    @DisplayName("서버가 뜬 지 오래됐는데 요청이 없으면 시작에서도 끊긴 것이다")
    void noRequestAfterGraceIsDisconnected() {
        clock.advance(McpConnectionLiveness.STARTUP_GRACE.plusSeconds(1));

        assertThat(connection.atStart(PROFILE)).isFalse();
    }


    @Test
    @DisplayName("요청이 와 있으면 기다리지 않고 살아 있다")
    void seenProfileIsConnectedWithoutWaiting() {
        liveness.seen(PROFILE);

        assertThat(connection.atStart(PROFILE)).isTrue();
        assertThat(connection.atEnd(PROFILE)).isTrue();
        verify(driver, never()).waitUntil(any(), any());
    }
}
