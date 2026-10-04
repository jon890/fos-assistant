package com.bifos.assistant.proactive.application;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentToolPolicy;
import com.bifos.assistant.hermes.HermesToolsetClient;
import com.bifos.assistant.proactive.application.model.CheckBlocker;
import com.bifos.assistant.proactive.application.model.CheckBlockerCode;
import com.bifos.assistant.proactive.application.model.CheckReadiness;
import com.bifos.assistant.skill.application.SkillCommandCatalog;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 그 에이전트로 살펴보기를 시작할 수 있는지 판정한다(ADR-077).
 *
 * <p>까닭은 {@code docs/backend/proactive-check.md} 의 「시작 전 점검」 순서대로 보고 걸린 것을 모두 모은다. 켜진 스킬과
 * toolset 을 읽다 Hermes 가 실패하면 그 예외를 그대로 올린다. 확인하지 못한 에이전트를 시작할 수 있다고 하지 않기 위해서다.
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

    /** 무엇을 읽고 무엇을 고를지 정하는 분야 지침 스킬의 이름이다. */
    static final String SKILL_NAME = "proactive-check";

    private final ProactiveCheckProperties properties;
    private final SkillCommandCatalog skills;
    private final HermesToolsetClient toolsets;

    public CheckReadiness check(Agent agent) {
        List<CheckBlocker> blockers = new ArrayList<>();
        if (!properties.enabled()) {
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
        List<String> notAllowed = toolsets.readEnabled(agent.apiBaseUrl(), agent.hermesProfile()).stream()
                .filter(name -> !ALLOWED_TOOLSETS.contains(name))
                .distinct()
                .sorted()
                .toList();
        if (!notAllowed.isEmpty()) {
            blockers.add(new CheckBlocker(CheckBlockerCode.TOOLSETS_NOT_ALLOWED, notAllowed));
        }
        return new CheckReadiness(blockers);
    }
}
