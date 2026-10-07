package com.bifos.assistant.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.hermes.StubHermesRunsClient;
import com.bifos.assistant.hermes.dto.SessionRuntime;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticApplicationContext;

class IntegrationTestIsolationTest {

    private static final Instant FIXED = Instant.parse("2020-01-01T00:00:00Z");

    private final TrackingBackgroundTasks tasks = new TrackingBackgroundTasks();
    private final StubHermesRunsClient stub = new StubHermesRunsClient();
    private final TestClock clock = new TestClock();
    private final StaticApplicationContext context = new StaticApplicationContext();

    @BeforeEach
    void setUp() {
        context.getBeanFactory().registerSingleton("tasks", tasks);
        context.getBeanFactory().registerSingleton("stub", stub);
        context.getBeanFactory().registerSingleton("clock", clock);
        context.refresh();
        stub.willReportSessionRuntime(new SessionRuntime("model", "provider"));
        clock.set(FIXED);
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    @Test
    @DisplayName("띄운 작업이 끝나면 afterTest 가 돌아오고 대역 응답과 시계의 고정 시각을 되돌린다")
    void returnsAfterTasksFinishAndResetsDoubles() throws InterruptedException {
        CountDownLatch mayFinish = new CountDownLatch(1);
        tasks.start("short-task", () -> awaitQuietly(mayFinish));
        Thread.ofVirtual().start(mayFinish::countDown);

        IntegrationTestIsolation.afterTest(context, Duration.ofSeconds(5));

        assertThat(stub.readSessionRuntime("url", "profile", "session"))
                .as("대역에 넣어 둔 세션 응답이 비워져야 한다")
                .isNull();
        assertThat(clock.instant()).as("시계가 고정 시각에서 풀려야 한다").isNotEqualTo(FIXED);
    }

    @Test
    @DisplayName("붙잡힌 작업이 있으면 그 스레드 이름을 담아 실패하고 대역과 시계는 되돌린다")
    void failsWithHeldThreadNameAndStillResetsDoubles() throws InterruptedException {
        CountDownLatch hold = new CountDownLatch(1);
        Thread held = tasks.start("held-turn", () -> awaitQuietly(hold));
        try {
            assertThatThrownBy(() -> IntegrationTestIsolation.afterTest(context, Duration.ofMillis(100)))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("held-turn");

            assertThat(stub.readSessionRuntime("url", "profile", "session"))
                    .as("실패해도 대역에 넣어 둔 세션 응답이 비워져야 한다")
                    .isNull();
            assertThat(clock.instant()).as("실패해도 시계가 고정 시각에서 풀려야 한다").isNotEqualTo(FIXED);
        } finally {
            hold.countDown();
            held.join();
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
