package com.bifos.assistant.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.connector.application.model.ConnectorActionChanged;
import com.bifos.assistant.hermes.StubHermesRunsClient;
import com.bifos.assistant.hermes.dto.SessionRuntime;
import com.bifos.assistant.shared.auth.UserAccessRevoked;
import com.bifos.assistant.shared.config.LiveProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
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
    private final FailingAccessRevoker revoker = new FailingAccessRevoker();
    private final ConnectorChangeRecorder recorder = new ConnectorChangeRecorder();
    private final StaticApplicationContext context = new StaticApplicationContext();

    @BeforeEach
    void setUp() {
        context.getBeanFactory().registerSingleton("tasks", tasks);
        context.getBeanFactory().registerSingleton("stub", stub);
        context.getBeanFactory().registerSingleton("clock", clock);
        context.getBeanFactory().registerSingleton("scheduler", new CapturingTaskScheduler());
        context.getBeanFactory().registerSingleton("retryThreads", new WakeRetryThreads());
        context.getBeanFactory().registerSingleton("recorder", recorder);
        context.getBeanFactory().registerSingleton("revoker", revoker);
        context.getBeanFactory().registerSingleton("results", new TestAutoTurnResultSource());
        context.getBeanFactory().registerSingleton("failing", new AttentionTestCandidates.FailingCandidates());
        context.getBeanFactory().registerSingleton("counting", new AttentionTestCandidates.ReadCountingCandidates());
        context.refresh();
        stub.willReportSessionRuntime(new SessionRuntime("model", "provider"));
        clock.set(FIXED);
        revoker.fail();
        recorder.start();
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    @Test
    @DisplayName("띄운 작업이 끝나면 afterTest 가 돌아오고 대역 응답과 시계의 고정 시각, 켜 둔 대역을 되돌린다")
    void returnsAfterTasksFinishAndResetsDoubles() throws InterruptedException {
        CountDownLatch mayFinish = new CountDownLatch(1);
        tasks.start("short-task", () -> awaitQuietly(mayFinish));
        Thread.ofVirtual().start(mayFinish::countDown);

        IntegrationTestIsolation.afterTest(context, Duration.ofSeconds(5));

        assertThat(stub.readSessionRuntime("url", "profile", "session"))
                .as("대역에 넣어 둔 세션 응답이 비워져야 한다")
                .isNull();
        assertThat(clock.instant()).as("시계가 고정 시각에서 풀려야 한다").isNotEqualTo(FIXED);
        assertThatCode(() -> revoker.on(new UserAccessRevoked(1L)))
                .as("켜 둔 폐기 실패 대역이 꺼져야 한다")
                .doesNotThrowAnyException();
        recorder.on(new ConnectorActionChanged(1L, UUID.randomUUID()));
        assertThat(recorder.seen()).as("켜 둔 사건 기록 대역이 꺼져야 한다").isEmpty();
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

    @Test
    @DisplayName("한 대역의 reset 이 던져도 설정과 다른 대역은 되돌리고, 처음 예외에 뒤의 예외를 붙여 던진다")
    void resetsSettingsAndOtherDoublesWhenOneDoubleResetThrows() {
        OverridableLiveProperties<String> setting =
                new OverridableLiveProperties<>(LiveProperties.fixed(String.class, "startup"));
        setting.override("changed");
        StaticApplicationContext broken = new StaticApplicationContext();
        try {
            broken.getBeanFactory().registerSingleton("tasks", new TrackingBackgroundTasks());
            broken.getBeanFactory().registerSingleton("setting", setting);
            broken.getBeanFactory().registerSingleton("stub", new StubHermesRunsClient() {
                @Override
                public void reset() {
                    throw new IllegalStateException("stub reset failed");
                }
            });
            broken.getBeanFactory().registerSingleton("clock", clock);
            broken.getBeanFactory().registerSingleton("scheduler", new CapturingTaskScheduler());
            broken.getBeanFactory().registerSingleton("retryThreads", new WakeRetryThreads() {
                @Override
                public void reset() {
                    throw new IllegalStateException("retry reset failed");
                }
            });
            broken.getBeanFactory().registerSingleton("recorder", recorder);
            broken.getBeanFactory().registerSingleton("revoker", revoker);
            broken.getBeanFactory().registerSingleton("results", new TestAutoTurnResultSource());
            broken.getBeanFactory().registerSingleton("failing", new AttentionTestCandidates.FailingCandidates());
            broken.getBeanFactory().registerSingleton("counting", new AttentionTestCandidates.ReadCountingCandidates());
            broken.refresh();

            assertThatThrownBy(() -> IntegrationTestIsolation.afterTest(broken, Duration.ofSeconds(5)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("stub reset failed")
                    .satisfies(thrown -> assertThat(thrown.getSuppressed())
                            .as("뒤에 던진 대역의 예외가 붙어야 한다")
                            .extracting(Throwable::getMessage)
                            .containsExactly("retry reset failed"));

            assertThat(setting.current()).as("바꾼 설정이 기동 값으로 돌아와야 한다").isEqualTo("startup");
            assertThat(clock.instant()).as("던진 대역 뒤의 시계도 고정 시각에서 풀려야 한다").isNotEqualTo(FIXED);
            assertThatCode(() -> revoker.on(new UserAccessRevoked(1L)))
                    .as("던진 대역 뒤의 폐기 실패 대역도 꺼져야 한다")
                    .doesNotThrowAnyException();
            recorder.on(new ConnectorActionChanged(1L, UUID.randomUUID()));
            assertThat(recorder.seen()).as("던진 대역 뒤의 사건 기록 대역도 꺼져야 한다").isEmpty();
        } finally {
            broken.close();
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
