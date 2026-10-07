package com.bifos.assistant.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.annotation.MergedAnnotations.SearchStrategy;

/** 변형 주석이 설정 값만 더하고 기반을 끌어오지 않는지 컨텍스트 없이 본다. */
class VariantAnnotationsTest {

    @DelegationWakeEnabled
    @SmallExecutionLimit
    static class BothVariants {}

    @SmallExecutionLimit
    static class OnlyVariant {}

    @LongProactiveCheckTimeouts
    @OverrideProperties("assistant.proactive-check.session-max-checks=2")
    static class VariantWithRemainingValue {}

    @OverrideProperties("assistant.user-execution.max-running=3")
    @SmallExecutionLimit
    static class DirectValueOverVariant {}

    static class SubclassOfVariant extends BothVariants {}

    @Test
    @DisplayName("변형을 함께 달면 병합된 속성에 두 값이 모두 있다")
    void mergesPropertiesOfBothVariants() {
        assertThat(IntegrationTestIsolation.overridesOf(BothVariants.class))
                .containsExactlyInAnyOrder(
                        "assistant.delegation-wake.enabled=true", "assistant.user-execution.max-running=2");
    }

    @Test
    @DisplayName("살펴보기 시간 상한 변형 뒤에 남는 값을 달면 병합된 속성에 세 값이 모두 있다")
    void mergesVariantWithRemainingValue() {
        assertThat(IntegrationTestIsolation.overridesOf(VariantWithRemainingValue.class))
                .containsExactlyInAnyOrder(
                        "hermes.run-timeout=30s",
                        "assistant.proactive-check.max-duration=20s",
                        "assistant.proactive-check.session-max-checks=2");
    }

    @Test
    @DisplayName("검사 클래스에 직접 단 값이 변형의 값보다 앞에 온다")
    void putsDirectValueBeforeVariantValue() {
        assertThat(IntegrationTestIsolation.overridesOf(DirectValueOverVariant.class))
                .containsExactly("assistant.user-execution.max-running=3", "assistant.user-execution.max-running=2");
    }

    @Test
    @DisplayName("상위 클래스에 단 변형의 값을 하위 클래스도 모은다")
    void collectsVariantsOfSuperclass() {
        assertThat(IntegrationTestIsolation.overridesOf(SubclassOfVariant.class))
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
