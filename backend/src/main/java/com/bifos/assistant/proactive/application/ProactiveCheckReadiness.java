package com.bifos.assistant.proactive.application;

import com.bifos.assistant.agent.application.AgentConnectorBindings;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentToolPolicy;
import com.bifos.assistant.hermes.HermesToolsetClient;
import com.bifos.assistant.proactive.application.model.CheckBlocker;
import com.bifos.assistant.proactive.application.model.CheckBlockerCode;
import com.bifos.assistant.proactive.application.model.CheckReadiness;
import com.bifos.assistant.shared.config.LiveProperties;
import com.bifos.assistant.skill.application.SkillCommandCatalog;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 그 에이전트로 살펴보기를 시작할 수 있는지 판정한다(ADR-080).
 *
 * <p>까닭은 {@code docs/features/proactive.md} 의 「시작 전 점검」 순서대로 보고 걸린 것을 모두 모은다. 켜진 스킬과
 * toolset 을 읽다 Hermes 가 실패하면 그 예외를 그대로 올린다. 확인하지 못한 에이전트를 시작할 수 있다고 하지 않기 위해서다.
 *
 * <p>그 에이전트에 붙은 커넥터 MCP 서버(ADR-083)는 쓰기 허용과 상관없이 받는다. 그 서버의 도구는 Control Plane 이 호출마다 판정해
 * 읽기만 하는 살펴보기에서는 읽기 도구만, 쓰기를 허용한 살펴보기에서는 나머지를 승인 카드로 보내므로 읽기 경계를 깨지 않는다. 붙지
 * 않은 다른 MCP 서버는 무엇을 하는지 판정하지 못해 지금처럼 막는다.
 */
@Component
@RequiredArgsConstructor
public class ProactiveCheckReadiness {

    /**
     * 살펴보기 에이전트에 켜 둘 수 있는 toolset 이다. 쓰기나 외부 연락이 되는 것, 사용자가 없는 실행에서 답을 기다리는 것,
     * Control Plane 이 세거나 멈추지 못하는 위임은 뺐다. 까닭은 문서의 「시작 전 점검」 표에 있다.
     */
    static final Set<String> ALLOWED_TOOLSETS =
            Set.of("web", "vision", "todo", AgentToolPolicy.SKILLS, AgentToolPolicy.CONTROL_PLANE_MCP);

    /**
     * 쓰기 도구를 허용한 에이전트(ADR-082)에서도 막는 toolset 이다. {@code delegation} 의 자식과 {@code cronjob} 이 건 예약 작업은
     * Control Plane 이 세지도 멈추지도 못하고, 예약 작업은 살펴보기가 끝난 뒤에도 돈다. {@code clarify} 는 답할 사람이 없는 실행에서 시간
     * 상한까지 기다리기만 한다.
     */
    static final Set<String> ALWAYS_BLOCKED_TOOLSETS = Set.of("delegation", "clarify", "cronjob");

    /** 무엇을 읽고 무엇을 고를지 정하는 분야 지침 스킬의 이름이다. */
    static final String SKILL_NAME = "proactive-check";

    private final LiveProperties<ProactiveCheckProperties> properties;
    private final SkillCommandCatalog skills;
    private final HermesToolsetClient toolsets;
    private final AgentConnectorBindings connectorBindings;

    public CheckReadiness check(Agent agent) {
        List<CheckBlocker> blockers = new ArrayList<>();
        if (!properties.current().enabled()) {
            blockers.add(CheckBlocker.of(CheckBlockerCode.DISABLED));
        }
        // 커넥터 에이전트와 흐름이 붙은 에이전트는 살펴보기를 하지 않으므로 Hermes 를 부를 까닭이 없다.
        if (agent.connectorManaged() || (agent.flow() != null && !agent.flow().isBlank())) {
            blockers.add(CheckBlocker.of(CheckBlockerCode.AGENT_NOT_SUPPORTED));
            return new CheckReadiness(blockers);
        }
        if (!skills.enabledNames(agent).contains(SKILL_NAME)) {
            blockers.add(CheckBlocker.of(CheckBlockerCode.SKILL_MISSING));
        }
        boolean writesAllowed = agent.proactiveCheckWritesAllowed();
        Set<String> connectorServers = connectorBindings.connectorServers(agent.id());
        List<String> notAllowed = toolsets.readEnabled(agent.apiBaseUrl(), agent.hermesProfile()).stream()
                .filter(name -> !connectorServers.contains(name) && !allowed(name, writesAllowed))
                .distinct()
                .sorted()
                .toList();
        if (!notAllowed.isEmpty()) {
            blockers.add(new CheckBlocker(CheckBlockerCode.TOOLSETS_NOT_ALLOWED, notAllowed));
        }
        return new CheckReadiness(blockers);
    }

    /**
     * 살펴보기 에이전트에 켜 둘 수 있는 toolset 인가. 쓰기 도구를 허용한 에이전트는 Control Plane 이 아는 toolset 전부와 Control Plane
     * MCP 를 받고 {@link #ALWAYS_BLOCKED_TOOLSETS} 만 막는다. 모르는 이름(다른 MCP 서버)은 무엇을 하는지 판정하지 못해 막는다. 붙은
     * 커넥터 서버는 이 판정 앞에서 따로 받는다.
     */
    private static boolean allowed(String name, boolean writesAllowed) {
        // 사용자 설정에 노출하지 않는 원본 조회는 현재 실행의 대화만 읽으므로 자동 추가 뒤에도 살펴보기를 막지 않는다.
        if (AgentToolPolicy.ATTACHMENT_INSPECTION.equals(name)) {
            return true;
        }
        if (!writesAllowed) {
            return ALLOWED_TOOLSETS.contains(name);
        }
        if (ALWAYS_BLOCKED_TOOLSETS.contains(name)) {
            return false;
        }
        return AgentToolPolicy.CONTROL_PLANE_MCP.equals(name) || AgentToolPolicy.isKnown(name);
    }
}
