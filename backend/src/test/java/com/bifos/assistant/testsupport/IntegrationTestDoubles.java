package com.bifos.assistant.testsupport;

import com.bifos.assistant.browser.domain.CdpProbe;
import com.bifos.assistant.hermes.StubHermesRunsClient;
import com.bifos.assistant.proactive.eval.ReplayDecisionProvider;
import com.bifos.assistant.shared.config.LiveProperties;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistrar;

/**
 * {@link BackendIntegrationTest} 의 공통 대역이다.
 *
 * <p>여기 둔 빈은 상태를 갖는다. {@link IntegrationTestIsolation} 이 검사마다 비운다. 몇 검사만 쓰는 대역은 꺼 두면 아무것도 하지
 * 않게 만들어 여기 둔다. 쓰는 검사가 켜고 공통 확장이 검사 뒤에 끈다. 꺼 둔 상태에서 운영 동작을 바꾸는 대역은 더하지 않는다. 모든
 * 검사의 동작이 바뀐다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class IntegrationTestDoubles {

    /** 표본 가격표 파일의 실제 경로를 담는 속성이다. {@link SamplePriceCatalog} 가 자리표시자로 읽는다. */
    public static final String SAMPLE_PRICE_CATALOG_PATH = "test.sample-price-catalog-path";

    /** 가격표의 버전은 파일 수정 시각에서 나온다. 검사가 같은 버전 문자열을 단언할 수 있게 시각을 고정한다. */
    private static final Instant SAMPLE_PRICE_CATALOG_MODIFIED_AT = Instant.parse("2026-09-17T04:00:00Z");

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

    /**
     * 기본은 모든 예약을 실제로 건다. 운영에 스케줄러 빈이 없어 이 빈이 {@code @Scheduled} 실행까지 맡는다.
     *
     * <p>Boot 자동 설정의 builder 로 만들어 운영 스케줄러와 같은 {@code spring.task.scheduling.*} 값(스레드 수, 이름 앞부분, 종료 대기)을
     * 받는다.
     */
    @Bean
    @Primary
    CapturingTaskScheduler capturingTaskScheduler(ThreadPoolTaskSchedulerBuilder builder) {
        return builder.configure(new CapturingTaskScheduler());
    }

    /** 기본은 아무것도 모으지 않는다. */
    @Bean
    WakeRetryThreads wakeRetryThreads() {
        return new WakeRetryThreads();
    }

    /** 기본은 아무것도 적지 않는다. */
    @Bean
    ConnectorChangeRecorder connectorChangeRecorder() {
        return new ConnectorChangeRecorder();
    }

    /** 기본은 사건을 받아도 아무것도 하지 않는다. */
    @Bean
    FailingAccessRevoker failingAccessRevoker() {
        return new FailingAccessRevoker();
    }

    /** 비어 있으면 결과를 내지 않는다. */
    @Bean
    TestAutoTurnResultSource testAutoTurnResultSource() {
        return new TestAutoTurnResultSource();
    }

    /** 후보를 내지 않는다. 기본은 읽기도 실패하지 않는다. */
    @Bean
    AttentionTestCandidates.FailingCandidates failingAttentionCandidates() {
        return new AttentionTestCandidates.FailingCandidates();
    }

    /** 후보를 내지 않고 읽은 횟수만 센다. */
    @Bean
    AttentionTestCandidates.ReadCountingCandidates readCountingAttentionCandidates() {
        return new AttentionTestCandidates.ReadCountingCandidates();
    }

    /** 운영 proxy 자리에 들어간다. 운영 코드는 브라우저 기능이 켜져 있을 때만 부른다. */
    @Bean
    @Primary
    FakeBrowserRuntime fakeBrowserRuntime() {
        return new FakeBrowserRuntime();
    }

    /** 늘 답하는 CDP 다. 상태가 없다. 운영 코드는 브라우저 기능이 켜져 있을 때만 부른다. */
    @Bean
    @Primary
    CdpProbe readyCdpProbe() {
        return address -> true;
    }

    /** 받은 조각을 되돌려 주는 Chrome 쪽 중계다. 운영 코드는 중계 WebSocket 이 열릴 때만 부른다. */
    @Bean
    @Primary
    EchoCdpRelayConnector echoCdpRelayConnector() {
        return new EchoCdpRelayConnector();
    }

    /**
     * 운영의 {@link LiveProperties} 빈을 모두 {@link OverridableLiveProperties} 로 감싼다. 빈 이름과 정의는 그대로라, 제네릭 타입으로
     * 주입받는 곳은 {@code @Bean} 메서드의 반환형으로 같은 빈을 받는다.
     */
    @Bean
    static BeanPostProcessor overridableLiveProperties() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof LiveProperties<?> live && !(bean instanceof OverridableLiveProperties<?>)) {
                    return new OverridableLiveProperties<>(live);
                }
                return bean;
            }
        };
    }

    /** 표본 가격표의 경로를 넣는다. 값을 읽을 때마다 파일의 수정 시각을 고정한다. */
    /**
     * 먼저 살펴보기 평가 검사의 판단 provider 다. 운영의 판단 경로는 provider 를 id 로만 고르므로, {@code fixture-} id 를 부르지
     * 않는 검사에는 영향이 없다.
     */
    @Bean
    ReplayDecisionProvider fixtureA() {
        return ReplayDecisionProvider.forEval("fixture-a", "REPLAY");
    }

    @Bean
    ReplayDecisionProvider fixtureB() {
        return ReplayDecisionProvider.forEval("fixture-b", "REPLAY");
    }

    @Bean
    ReplayDecisionProvider fixtureUnavailable() {
        return ReplayDecisionProvider.forEval("fixture-unavailable", "UNAVAILABLE");
    }

    @Bean
    ReplayDecisionProvider fixtureTimeout() {
        return ReplayDecisionProvider.forEval("fixture-timeout", "TIMEOUT");
    }

    @Bean
    ReplayDecisionProvider fixtureError() {
        return ReplayDecisionProvider.forEval("fixture-error", "ERROR");
    }

    @Bean
    ReplayDecisionProvider fixtureInvalid() {
        return ReplayDecisionProvider.forEval("fixture-invalid", "INVALID");
    }

    @Bean
    DynamicPropertyRegistrar samplePriceCatalogPath() {
        return registry ->
                registry.add(SAMPLE_PRICE_CATALOG_PATH, () -> sampleCatalog().toString());
    }

    private static Path sampleCatalog() {
        try {
            Path file = Path.of(IntegrationTestDoubles.class
                    .getResource("/pricing/models-dev-sample.json")
                    .toURI());
            Files.setLastModifiedTime(file, FileTime.from(SAMPLE_PRICE_CATALOG_MODIFIED_AT));
            return file;
        } catch (URISyntaxException | IOException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
