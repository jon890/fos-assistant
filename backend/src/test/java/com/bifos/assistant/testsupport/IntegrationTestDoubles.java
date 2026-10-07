package com.bifos.assistant.testsupport;

import com.bifos.assistant.hermes.StubHermesRunsClient;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * {@link BackendIntegrationTest} 의 공통 대역이다.
 *
 * <p>여기 둔 빈은 상태를 갖는다. {@link IntegrationTestIsolation} 이 검사마다 비운다. 검사 클래스 하나만 쓰는 대역을 여기 더하지
 * 않는다. 더하면 모든 검사의 동작이 바뀐다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class IntegrationTestDoubles {

    @Bean
    @Primary
    StubHermesRunsClient stubHermesRunsClient() {
        return new StubHermesRunsClient();
    }

    /** 운영 시계 빈을 받는 곳이 이 시계를 받는다. */
    @Bean
    @Primary
    TestClock testClock() {
        return new TestClock();
    }

    /** 운영 코드가 띄우는 요청 밖 작업을 쥐어, 검사가 끝날 때 기다린다. */
    @Bean
    @Primary
    TrackingBackgroundTasks trackingBackgroundTasks() {
        return new TrackingBackgroundTasks();
    }
}
