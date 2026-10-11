package com.bifos.assistant.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.agent.config.AgentProperties;
import com.bifos.assistant.agent.config.PeopleProperties;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** 설정의 명시 값, 단위와 잘못된 값의 기동 거절을 package 이동 전후에 확인한다. */
class AgentConfigurationBindingTest {

    @Test
    @DisplayName("에이전트 설정의 명시 값과 기간 단위를 바인딩한다")
    void bindsExplicitAgentValues() {
        Binder binder = binder(Map.of("assistant.agents.max-per-user", "9", "assistant.agents.purge-after", "36h"));
        AgentProperties properties = binder.bind("assistant.agents", AgentProperties.class).get();
        assertThat(properties.maxPerUser()).isEqualTo(9);
        assertThat(properties.purgeAfter()).isEqualTo(Duration.ofHours(36));
    }

    @Test
    @DisplayName("첫 에이전트 비용 방식과 계정 범위와 도구를 바인딩한다")
    void bindsExplicitFirstAgentValues() {
        Binder binder = binder(Map.of(
                "assistant.people.default-cost-mode", "API",
                "assistant.people.default-credential-scope", "DEDICATED",
                "assistant.people.default-toolsets[0]", "web",
                "assistant.people.default-toolsets[1]", "terminal"));
        PeopleProperties properties = binder.bind("assistant.people", PeopleProperties.class).get();
        assertThat(properties.defaultCostMode()).isEqualTo(CostMode.API);
        assertThat(properties.defaultCredentialScope()).isEqualTo(CredentialScope.DEDICATED);
        assertThat(properties.defaultToolsets()).containsExactly("web", "terminal");
    }

    @Test
    @DisplayName("설정이 없으면 에이전트와 첫 에이전트의 기본값을 쓴다")
    void bindsDefaults() {
        Binder binder = binder(Map.of());
        AgentProperties agents = binder.bindOrCreate("assistant.agents", AgentProperties.class);
        PeopleProperties people = binder.bindOrCreate("assistant.people", PeopleProperties.class);
        assertThat(agents.maxPerUser()).isEqualTo(5);
        assertThat(agents.purgeAfter()).isEqualTo(Duration.ofDays(7));
        assertThat(people.defaultCostMode()).isEqualTo(CostMode.SUBSCRIPTION);
        assertThat(people.defaultCredentialScope()).isEqualTo(CredentialScope.SHARED_HOUSEHOLD);
        assertThat(people.defaultToolsets()).isEmpty();
    }

    @Test
    @DisplayName("음수 기간과 허용하지 않은 기본 도구의 바인딩을 거절한다")
    void rejectsInvalidBoundValues() {
        assertThatThrownBy(() -> binder(Map.of("assistant.agents.purge-after", "-1s"))
                .bind("assistant.agents", AgentProperties.class)).hasRootCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> binder(Map.of("assistant.people.default-toolsets[0]", "memory"))
                .bind("assistant.people", PeopleProperties.class)).hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    private static Binder binder(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values));
    }
}
