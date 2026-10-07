package com.bifos.assistant.testsupport;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;

/**
 * 가격표 경로를 클래스패스의 표본 가격표 파일로 넣는다. {@link SamplePriceCatalog} 가 가져온다.
 *
 * <p>가격표의 버전은 파일 수정 시각에서 나온다. 검사가 같은 버전 문자열을 단언할 수 있게 시각을 고정한다.
 */
@TestConfiguration
public class SamplePriceCatalogProperties {

    private static final Instant MODIFIED_AT = Instant.parse("2026-09-17T04:00:00Z");

    @Bean
    DynamicPropertyRegistrar samplePriceCatalogPath() {
        return registry -> registry.add(
                "assistant.pricing.catalog-path", () -> sampleCatalog().toString());
    }

    private static Path sampleCatalog() {
        try {
            Path file = Path.of(SamplePriceCatalogProperties.class
                    .getResource("/pricing/models-dev-sample.json")
                    .toURI());
            Files.setLastModifiedTime(file, FileTime.from(MODIFIED_AT));
            return file;
        } catch (URISyntaxException | IOException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
