package com.bifos.assistant.proactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.application.AgentConnectorBindings;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.hermes.HermesToolsetClient;
import com.bifos.assistant.proactive.application.ProactiveCheckProperties;
import com.bifos.assistant.proactive.application.ProactiveCheckReadiness;
import com.bifos.assistant.proactive.application.model.CheckBlocker;
import com.bifos.assistant.proactive.application.model.CheckBlockerCode;
import com.bifos.assistant.proactive.application.model.CheckReadiness;
import com.bifos.assistant.shared.config.LiveProperties;
import com.bifos.assistant.skill.application.SkillCommandCatalog;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 살펴보기를 시작하기 전에 막는 까닭을 모으는 판정을 본다. Hermes 는 대역으로 둔다. */
class ProactiveCheckReadinessTest {

    private static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");
    private static final Duration MINUTE = Duration.ofMinutes(1);

    private final HermesToolsetClient toolsets = mock(HermesToolsetClient.class);
    private final SkillCommandCatalog skills = mock(SkillCommandCatalog.class);
    private final AgentConnectorBindings connectorBindings = mock(AgentConnectorBindings.class);
    private final Agent agent = agent("career");

    private ProactiveCheckReadiness readiness(boolean enabled) {
        return new ProactiveCheckReadiness(
                LiveProperties.fixed(
                        ProactiveCheckProperties.class,
                        new ProactiveCheckProperties(enabled, MINUTE, 40, 3, 14, MINUTE, 20)),
                skills,
                toolsets,
                connectorBindings);
    }

    private static Agent agent(String code) {
        return Agent.of(
                code,
                code,
                code + "-profile",
                "http://agent-runtime.test/p/" + code,
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE,
                1L,
                NOW);
    }

    private void enabled(List<String> enabledToolsets, Set<String> enabledSkills) {
        when(toolsets.readEnabled(agent.apiBaseUrl(), agent.hermesProfile())).thenReturn(enabledToolsets);
        when(skills.enabledNames(agent)).thenReturn(enabledSkills);
    }

    private static List<CheckBlockerCode> codes(CheckReadiness readiness) {
        return readiness.blockers().stream().map(CheckBlocker::code).toList();
    }

    @Test
    @DisplayName("허용한 toolset 만 켜져 있고 분야 지침 스킬이 있으면 막는 까닭이 없다")
    void availableWhenOnlyAllowedToolsetsAndSkillPresent() {
        enabled(List.of("web", "vision", "todo", "skills", "fos-assistant"), Set.of("proactive-check", "resume"));

        CheckReadiness result = readiness(true).check(agent);

        assertThat(result.blockers()).as("막는 까닭").isEmpty();
        assertThat(result.available()).isTrue();
    }

    @Test
    @DisplayName("delegation 과 terminal 이 켜져 있으면 두 이름을 정렬해 TOOLSETS_NOT_ALLOWED 에 싣는다")
    void reportsDisallowedToolsetsSorted() {
        enabled(List.of("terminal", "web", "skills", "delegation", "fos-assistant"), Set.of("proactive-check"));

        CheckReadiness result = readiness(true).check(agent);

        assertThat(result.blockers())
                .as("막는 까닭")
                .containsExactly(
                        new CheckBlocker(CheckBlockerCode.TOOLSETS_NOT_ALLOWED, List.of("delegation", "terminal")));
        assertThat(result.available()).isFalse();
    }

    @Test
    @DisplayName("켜진 스킬에 분야 지침이 없으면 SKILL_MISSING 이다")
    void reportsSkillMissing() {
        enabled(List.of("web", "skills", "fos-assistant"), Set.of("resume"));

        CheckReadiness result = readiness(true).check(agent);

        assertThat(codes(result)).as("막는 까닭").containsExactly(CheckBlockerCode.SKILL_MISSING);
    }

    @Test
    @DisplayName("커넥터 에이전트는 Hermes 를 부르지 않고 AGENT_NOT_SUPPORTED 다")
    void connectorAgentIsNotSupportedWithoutCallingHermes() {
        agent.markConnectorManaged();

        CheckReadiness result = readiness(true).check(agent);

        assertThat(codes(result)).as("막는 까닭").containsExactly(CheckBlockerCode.AGENT_NOT_SUPPORTED);
        verifyNoInteractions(toolsets, skills);
    }

    @Test
    @DisplayName("흐름이 붙은 에이전트도 Hermes 를 부르지 않고 AGENT_NOT_SUPPORTED 다")
    void flowAgentIsNotSupportedWithoutCallingHermes() {
        agent.assignFlow("planner");

        CheckReadiness result = readiness(true).check(agent);

        assertThat(codes(result)).as("막는 까닭").containsExactly(CheckBlockerCode.AGENT_NOT_SUPPORTED);
        verifyNoInteractions(toolsets, skills);
    }

    @Test
    @DisplayName("설정을 끄면 다른 조건이 모두 맞아도 DISABLED 다")
    void disabledBlocksEvenWhenEverythingElseIsReady() {
        enabled(List.of("web", "skills", "fos-assistant"), Set.of("proactive-check"));

        CheckReadiness result = readiness(false).check(agent);

        assertThat(codes(result)).as("막는 까닭").containsExactly(CheckBlockerCode.DISABLED);
        assertThat(result.available()).isFalse();
    }

    @Test
    @DisplayName("설정을 끄면 DISABLED 를 맨 앞에 두고 나머지 까닭도 함께 모은다")
    void disabledComesFirstAndOtherBlockersAreStillCollected() {
        enabled(List.of("web", "skills", "fos-assistant"), Set.of());

        CheckReadiness result = readiness(false).check(agent);

        assertThat(codes(result))
                .as("막는 까닭")
                .containsExactly(CheckBlockerCode.DISABLED, CheckBlockerCode.SKILL_MISSING);
        assertThat(result.available()).isFalse();
    }

    @Test
    @DisplayName("쓰기 허용이 꺼져 있으면 terminal 하나만 켜져도 TOOLSETS_NOT_ALLOWED 다")
    void writesOffBlocksTerminal() {
        enabled(List.of("web", "skills", "terminal", "fos-assistant"), Set.of("proactive-check"));

        CheckReadiness result = readiness(true).check(agent);

        assertThat(result.blockers())
                .as("막는 까닭")
                .containsExactly(new CheckBlocker(CheckBlockerCode.TOOLSETS_NOT_ALLOWED, List.of("terminal")));
    }

    @Test
    @DisplayName("쓰기 허용을 켜면 terminal, file 같은 관리자 등급 toolset 과 Control Plane MCP 가 켜져 있어도 막는 까닭이 없다")
    void writesOnAllowsKnownToolsetsAndControlPlaneMcp() {
        agent.changeProactiveCheckWritesAllowed(true);
        enabled(
                List.of("web", "skills", "terminal", "file", "browser", "code_execution", "tts", "fos-assistant"),
                Set.of("proactive-check"));

        CheckReadiness result = readiness(true).check(agent);

        assertThat(result.blockers()).as("막는 까닭").isEmpty();
        assertThat(result.available()).isTrue();
    }

    @Test
    @DisplayName("쓰기 허용을 켜도 delegation, clarify, cronjob 과 모르는 MCP 서버는 정렬해 TOOLSETS_NOT_ALLOWED 에 싣는다")
    void writesOnStillBlocksDelegationClarifyCronjobAndUnknownServers() {
        agent.changeProactiveCheckWritesAllowed(true);
        enabled(
                List.of("terminal", "delegation", "web", "other-mcp", "cronjob", "clarify", "fos-assistant"),
                Set.of("proactive-check"));

        CheckReadiness result = readiness(true).check(agent);

        assertThat(result.blockers())
                .as("막는 까닭")
                .containsExactly(new CheckBlocker(
                        CheckBlockerCode.TOOLSETS_NOT_ALLOWED,
                        List.of("clarify", "cronjob", "delegation", "other-mcp")));
        assertThat(result.available()).isFalse();
    }

    @Test
    @DisplayName("붙은 커넥터 서버가 켜져 있으면 읽기 전용에서도 막는 까닭이 없다")
    void boundConnectorServerIsAllowedWhenWritesOff() {
        when(connectorBindings.connectorServers(agent.id())).thenReturn(Set.of("career"));
        enabled(List.of("web", "skills", "fos-assistant", "career"), Set.of("proactive-check"));

        CheckReadiness result = readiness(true).check(agent);

        assertThat(result.blockers()).as("막는 까닭").isEmpty();
        assertThat(result.available()).isTrue();
    }

    @Test
    @DisplayName("붙은 커넥터 서버가 켜져 있으면 쓰기 허용에서도 막는 까닭이 없다")
    void boundConnectorServerIsAllowedWhenWritesOn() {
        agent.changeProactiveCheckWritesAllowed(true);
        when(connectorBindings.connectorServers(agent.id())).thenReturn(Set.of("career"));
        enabled(List.of("web", "skills", "fos-assistant", "career"), Set.of("proactive-check"));

        CheckReadiness result = readiness(true).check(agent);

        assertThat(result.blockers()).as("막는 까닭").isEmpty();
        assertThat(result.available()).isTrue();
    }

    @Test
    @DisplayName("붙지 않은 MCP 서버는 다른 서버가 붙어 있어도 읽기 전용과 쓰기 허용 모두 TOOLSETS_NOT_ALLOWED 다")
    void unboundMcpServerIsStillNotAllowed() {
        when(connectorBindings.connectorServers(agent.id())).thenReturn(Set.of("career"));
        enabled(List.of("web", "skills", "career", "other-mcp"), Set.of("proactive-check"));

        CheckReadiness readOnly = readiness(true).check(agent);
        agent.changeProactiveCheckWritesAllowed(true);
        CheckReadiness writes = readiness(true).check(agent);

        CheckBlocker expected = new CheckBlocker(CheckBlockerCode.TOOLSETS_NOT_ALLOWED, List.of("other-mcp"));
        assertThat(readOnly.blockers()).as("읽기 전용").containsExactly(expected);
        assertThat(writes.blockers()).as("쓰기 허용").containsExactly(expected);
    }

    @Test
    @DisplayName("연결이 붙은 에이전트라도 옛 커넥터 에이전트는 AGENT_NOT_SUPPORTED 다")
    void legacyConnectorAgentIsStillNotSupportedEvenWithBindings() {
        agent.markConnectorManaged();
        when(connectorBindings.connectorServers(agent.id())).thenReturn(Set.of("career"));

        CheckReadiness result = readiness(true).check(agent);

        assertThat(codes(result)).as("막는 까닭").containsExactly(CheckBlockerCode.AGENT_NOT_SUPPORTED);
        verifyNoInteractions(toolsets, skills);
    }
}
