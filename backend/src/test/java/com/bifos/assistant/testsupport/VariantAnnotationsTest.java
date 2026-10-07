package com.bifos.assistant.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.annotation.MergedAnnotations.SearchStrategy;
import org.springframework.test.context.TestPropertySource;

/** 변형 주석이 설정 값만 더하고 기반을 끌어오지 않는지 컨텍스트 없이 본다. */
class VariantAnnotationsTest {

    @DelegationWakeEnabled
    @SmallExecutionLimit
    static class BothVariants {}

    @SmallExecutionLimit
    static class OnlyVariant {}

    @Test
    @DisplayName("변형을 함께 달면 병합된 속성에 두 값이 모두 있다")
    void mergesPropertiesOfBothVariants() {
        String[] properties = MergedAnnotations.from(BothVariants.class, SearchStrategy.TYPE_HIERARCHY).stream(
                        TestPropertySource.class)
                .flatMap(annotation -> Arrays.stream(annotation.getStringArray("properties")))
                .toArray(String[]::new);

        assertThat(properties)
                .containsExactlyInAnyOrder(
                        "assistant.delegation-wake.enabled=true", "assistant.user-execution.max-running=2");
    }

    @Test
    @DisplayName("변형 주석만 단 클래스는 기반을 끌어오지 않는다")
    void variantDoesNotPullInTheBase() {
        MergedAnnotations annotations = MergedAnnotations.from(OnlyVariant.class, SearchStrategy.TYPE_HIERARCHY);

        assertThat(annotations.isPresent(SpringBootTest.class))
                .as("변형 주석이 @SpringBootTest 를 끌어왔다")
                .isFalse();
        assertThat(annotations.isPresent(BackendIntegrationTest.class))
                .as("변형 주석이 @BackendIntegrationTest 를 끌어왔다")
                .isFalse();
    }
}
