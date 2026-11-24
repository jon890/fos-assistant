package com.bifos.assistant.hermes;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.hermes.config.HermesProperties;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Hermes 실행 한도 두 값의 기본값과 관계를 고정한다(docs/features/execution.md 의 「실행 한도」). */
class HermesPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(Bind.class)
        .withPropertyValues(
            "hermes.profile-key-dir=keys",
            "hermes.dashboard-base-url=https://hermes-dashboard.example.com",
            "hermes.dashboard-token=test-dashboard-token",
            "hermes.shared-listener-base-url=https://hermes-listener.example.com");


    @Test
    @DisplayName("한도를 적지 않으면 사람이 기다리는 실행은 5분, 사람이 기다리지 않는 실행은 20분이다")
    void defaults() {
        runner.run(context -> {
            HermesProperties properties = context.getBean(HermesProperties.class);
            assertThat(properties.runTimeout()).isEqualTo(Duration.ofMinutes(5));
            assertThat(properties.backgroundRunTimeout()).isEqualTo(Duration.ofMinutes(20));
        });
    }


    @Test
    @DisplayName("hermes.background-run-timeout 을 적으면 그 값으로 묶인다")
    void bindsBackgroundRunTimeout() {
        runner.withPropertyValues("hermes.background-run-timeout=30m").run(context -> assertThat(
            context.getBean(HermesProperties.class).backgroundRunTimeout()).isEqualTo(Duration.ofMinutes(30)));
    }


    @Test
    @DisplayName("긴 한도를 hermes.run-timeout 보다 짧게 주면 hermes.run-timeout 으로 올린다")
    void backgroundIsNotShorterThanRunTimeout() {
        runner.withPropertyValues("hermes.run-timeout=10m", "hermes.background-run-timeout=1m").run(context -> assertThat(
            context.getBean(HermesProperties.class).backgroundRunTimeout()).isEqualTo(Duration.ofMinutes(10)));
    }


    @EnableConfigurationProperties(HermesProperties.class)
    static class Bind {
    }
}
