package com.bifos.assistant.skill.infra;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentToolPolicy;
import com.bifos.assistant.hermes.HermesSkillClient;
import com.bifos.assistant.hermes.HermesSkillClient.HermesSkill;
import com.bifos.assistant.hermes.HermesToolsetClient;
import com.bifos.assistant.shared.auth.CurrentUser;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 스킬 버전 디렉터리를 Hermes 에 게시하고 대시보드의 스킬 목록을 읽는다.
 *
 * <p>게시와 {@code skills} toolset 켜기는 한 번의 설정 쓰기다. 두 번으로 나누면 첫째만 성공했을 때
 * 스킬이 있는데 모델이 읽지 못하는 상태가 남는다. 어느 도구 목록을 함께 쓸지 여기서 정한다.
 */
@Component
@RequiredArgsConstructor
public class SkillPublisher {

    private final HermesSkillClient skills;
    private final HermesToolsetClient toolsets;

    public List<HermesSkill> list(String profile) {
        return skills.list(profile);
    }

    public void toggle(String profile, String name, boolean enabled) {
        skills.toggle(profile, name, enabled);
    }

    /** 그 에이전트의 API 실행에 {@code skills} toolset 이 켜져 있는가. */
    public boolean skillsToolsetEnabled(Agent agent) {
        return enabledToolsets(agent).contains(AgentToolPolicy.SKILLS);
    }

    /**
     * 그 경로를 {@code skills.external_dirs} 로 게시한다.
     *
     * <p>{@code skills} 가 아직 꺼져 있으면 지금 켜진 도구에 {@code skills} 를 더한 목록을 같은 본문에
     * 쓴다. 이미 켜져 있거나 빈 목록(마지막 스킬을 지움)을 게시할 때는 도구를 건드리지 않는다.
     *
     * @param connectorServers 그 에이전트에 붙은 커넥터의 MCP 서버 이름. 도구 목록을 쓸 때 함께 보낸다. 빠지면 대시보드가
     *     거절한다(ADR-083)
     */
    public void publish(CurrentUser user, Agent agent, List<String> externalDirs, Set<String> connectorServers) {
        List<String> apiServerToolsets =
                externalDirs.isEmpty() ? null : toolsetsWithSkills(user, agent, connectorServers);
        skills.publish(agent.hermesProfile(), externalDirs, apiServerToolsets);
    }

    private List<String> toolsetsWithSkills(CurrentUser user, Agent agent, Set<String> connectorServers) {
        List<String> enabled = enabledToolsets(agent);
        if (enabled.contains(AgentToolPolicy.SKILLS)) {
            return null;
        }
        List<String> requested = new ArrayList<>(
                enabled.stream().filter(AgentToolPolicy::isKnown).toList());
        requested.add(AgentToolPolicy.SKILLS);
        return AgentToolPolicy.requestedForWrite(user, agent, requested, enabled, connectorServers);
    }

    private List<String> enabledToolsets(Agent agent) {
        return toolsets.readEnabled(agent.apiBaseUrl(), agent.hermesProfile());
    }
}
