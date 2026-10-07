package com.bifos.assistant.testsupport;

import com.bifos.assistant.hermes.StubHermesRunsClient;
import java.time.Duration;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * 컨텍스트를 함께 쓰는 검사 사이에 남는 것을 정리한다.
 *
 * <p>turn, 위임, 살펴보기 같은 요청 밖 작업은 가상 스레드에서 돈다. 검사가 끝난 뒤에도 돌던 작업은 다음 검사가 정한 대역 응답을 가져가거나
 * 대역에 받은 명령을 남긴다. 그래서 검사가 끝나면 {@link TrackingBackgroundTasks} 가 쥔 스레드가 모두 끝날 때까지 join 한 뒤 대역과
 * 시계를 되돌린다(ADR-20261007 / background-tasks). 상한 안에 끝나지 않으면 그 검사를 실패로 둔다. 남은 작업을 알리지 않고 넘기면 원인과 먼 다음 검사가
 * 흔들린다. 상한은 판정 기준이 아니라 멈춘 작업을 잡는 안전장치다.
 *
 * <p>Mockito mock 과 spy 는 Spring 이 검사마다 초기화하므로 여기서 다루지 않는다.
 */
public class IntegrationTestIsolation implements BeforeEachCallback, AfterEachCallback {

    private static final Duration IDLE_LIMIT = Duration.ofSeconds(30);

    @Override
    public void beforeEach(ExtensionContext context) {
        reset(SpringExtension.getApplicationContext(context));
    }

    @Override
    public void afterEach(ExtensionContext context) throws InterruptedException {
        afterTest(SpringExtension.getApplicationContext(context), IDLE_LIMIT);
    }

    /**
     * 띄운 작업이 모두 끝나기를 기다린 뒤 대역과 시계를 되돌린다. 기다리다 실패해도 되돌린다.
     *
     * @throws AssertionError 상한이 지나도 끝나지 않은 작업이 있을 때. 메시지에 그 스레드 이름이 있다
     */
    static void afterTest(ApplicationContext context, Duration limit) throws InterruptedException {
        try {
            context.getBean(TrackingBackgroundTasks.class).awaitIdle(limit);
        } finally {
            reset(context);
        }
    }

    private static void reset(ApplicationContext context) {
        // Hermes 실행 클라이언트를 mock 으로 바꾼 검사에는 대역이 없다
        context.getBeanProvider(StubHermesRunsClient.class).ifAvailable(StubHermesRunsClient::reset);
        context.getBean(TestClock.class).reset();
    }
}
