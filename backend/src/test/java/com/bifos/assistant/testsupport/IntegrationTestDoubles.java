package com.bifos.assistant.testsupport;

import com.bifos.assistant.hermes.StubHermesRunsClient;
import com.bifos.assistant.shared.config.LiveProperties;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistrar;

/**
 * {@link BackendIntegrationTest} 의 공통 대역이다.
 *
 * <p>여기 둔 빈은 상태를 갖는다. {@link IntegrationTestIsolation} 이 검사마다 비운다. 검사 클래스 하나만 쓰는 대역을 여기 더하지
 * 않는다. 더하면 모든 검사의 동작이 바뀐다.
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
