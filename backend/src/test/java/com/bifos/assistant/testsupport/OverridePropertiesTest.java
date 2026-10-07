package com.bifos.assistant.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.chat.application.DelegationWakeProperties;
import com.bifos.assistant.connector.application.ConnectorPolicyProperties;
import com.bifos.assistant.hermes.HermesProperties;
import com.bifos.assistant.orchestration.application.DelegationProperties;
import com.bifos.assistant.shared.config.LiveProperties;
import com.bifos.assistant.usage.application.UserExecutionProperties;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/** {@link OverrideProperties} 가 컨텍스트를 새로 띄우지 않고 {@link LiveProperties} 의 값을 바꾸고 되돌리는지 본다. */
@BackendIntegrationTest
@OverrideProperties("assistant.user-execution.max-running=2")
class OverridePropertiesTest {

    /** test profile 의 {@code assistant.user-execution.max-running} 이다. */
    private static final int STARTUP_MAX_RUNNING = 1000;

    @Autowired
    ApplicationContext context;

    @Autowired
    LiveProperties<UserExecutionProperties> userExecution;

    @Autowired
    LiveProperties<DelegationWakeProperties> delegationWake;

    @Autowired
    LiveProperties<DelegationProperties> delegation;

    @Autowired
    LiveProperties<HermesProperties> hermes;

    @Autowired
    LiveProperties<ConnectorPolicyProperties> connectorPolicy;

    @Test
    @DisplayName("검사 클래스에 단 값이 LiveProperties 의 현재 값이 된다")
    void appliesValueOfTestClass() {
        assertThat(userExecution.current().maxRunning()).isEqualTo(2);
    }

    @Test
    @DisplayName("정리 메서드를 부르면 기동 값으로 돌아온다")
    void restoresStartupValueAfterTest() throws InterruptedException {
        IntegrationTestIsolation.afterTest(context, Duration.ofSeconds(5));

        assertThat(userExecution.current().maxRunning()).isEqualTo(STARTUP_MAX_RUNNING);
    }

    @Test
    @DisplayName("어느 LiveProperties 의 prefix 에도 속하지 않는 키는 그 키 이름을 담아 실패한다")
    void rejectsKeyOutsideEveryPrefix() {
        assertThatThrownBy(() -> IntegrationTestIsolation.apply(context, List.of("assistant.no-such-setting.value=1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("assistant.no-such-setting.value");
    }

    @Test
    @DisplayName("LiveProperties 로 읽지 않는 hermes 키는 그 키 이름을 담아 실패하고 아무것도 바꾸지 않는다")
    void rejectsHermesKeyNotReadThroughLiveProperties() {
        HermesProperties before = hermes.current();

        assertThatThrownBy(() -> IntegrationTestIsolation.apply(
                        context, List.of("hermes.poll-interval=20ms", "hermes.read-timeout=1s")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("hermes.read-timeout");
        assertThat(hermes.current()).isSameAs(before);
    }

    @Test
    @DisplayName("기동 때만 읽는 커넥터 정책의 만료 일정 키는 그 키 이름을 담아 실패하고 아무것도 바꾸지 않는다")
    void rejectsConnectorExpireCronReadOnlyAtStartup() {
        ConnectorPolicyProperties before = connectorPolicy.current();

        assertThatThrownBy(() -> IntegrationTestIsolation.apply(
                        context,
                        List.of(
                                "assistant.connector.policy.approval-ttl=1h",
                                "assistant.connector.policy.expire-cron=* * * * * *")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("assistant.connector.policy.expire-cron");
        assertThat(connectorPolicy.current()).isSameAs(before);
    }

    @Test
    @DisplayName("앞부분이 겹치는 prefix 는 마침표 경계로 나눠 깨우기 설정만 바꾸고 위임 설정은 그대로 둔다")
    void splitsOverlappingPrefixesAtDotBoundary() {
        DelegationProperties delegationBefore = delegation.current();
        assertThat(delegationWake.current().enabled())
                .as("test profile 은 깨우기를 꺼 둔다")
                .isFalse();

        IntegrationTestIsolation.apply(context, List.of("assistant.delegation-wake.enabled=true"));

        assertThat(delegationWake.current().enabled()).isTrue();
        assertThat(delegation.current()).isSameAs(delegationBefore);
    }

    @Test
    @DisplayName("살펴보기 시간 상한이 Hermes 실행 상한보다 길어지면 운영 기동 검사와 같은 조건으로 실패한다")
    void rejectsMaxDurationNotShorterThanRunTimeout() {
        assertThatThrownBy(() ->
                        IntegrationTestIsolation.apply(context, List.of("assistant.proactive-check.max-duration=5s")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-duration must be shorter than hermes.run-timeout");
    }
}
