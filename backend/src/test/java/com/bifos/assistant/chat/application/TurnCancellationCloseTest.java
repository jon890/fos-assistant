package com.bifos.assistant.chat.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.bifos.assistant.hermes.HermesRunsClient;
import com.bifos.assistant.usage.application.UserExecutionLimiter;
import com.bifos.assistant.usage.application.UserExecutionProperties;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TurnCancellationCloseTest {

    private final TurnCancellation turns = new TurnCancellation(
            mock(HermesRunsClient.class),
            Duration.ofSeconds(1),
            new UserExecutionLimiter(new UserExecutionProperties(1000, 0, null), mock(AgentExecutionRepository.class)));

    @AfterEach
    void tearDown() {
        turns.shutdown();
    }

    @Test
    @DisplayName("turn 을 닫으면 리스너가 stopped 가 거짓인 TurnClosed 를 받는다")
    void listenerReceivesTurnClosedWithStoppedFalse() {
        List<TurnClosed> closed = new ArrayList<>();
        turns.addCloseListener(closed::add);

        turns.close(turns.open(1L, 10L));

        assertThat(closed).containsExactly(new TurnClosed(10L, false));
    }

    @Test
    @DisplayName("중지로 표시한 turn 을 닫으면 stopped 가 참이다")
    void listenerReceivesStoppedTrueWhenMarkedStopped() {
        List<TurnClosed> closed = new ArrayList<>();
        turns.addCloseListener(closed::add);
        TurnHandle handle = turns.open(1L, 10L);

        turns.markStopped(handle);
        turns.close(handle);

        assertThat(closed).containsExactly(new TurnClosed(10L, true));
    }

    @Test
    @DisplayName("리스너가 예외를 던져도 닫기는 끝나고 다음 리스너가 불린다")
    void closeFinishesAndCallsNextListenerWhenListenerThrows() {
        List<TurnClosed> closed = new ArrayList<>();
        turns.addCloseListener(event -> {
            throw new IllegalStateException("listener failure");
        });
        turns.addCloseListener(closed::add);

        turns.close(turns.open(1L, 10L));

        assertThat(closed).containsExactly(new TurnClosed(10L, false));
    }

    @Test
    @DisplayName("같은 handle 을 두 번 닫아도 리스너는 한 번만 불린다")
    void listenerIsCalledOnceWhenSameHandleIsClosedTwice() {
        List<TurnClosed> closed = new ArrayList<>();
        turns.addCloseListener(closed::add);
        TurnHandle handle = turns.open(1L, 10L);

        turns.close(handle);
        turns.close(handle);

        assertThat(closed).hasSize(1);
    }
}
