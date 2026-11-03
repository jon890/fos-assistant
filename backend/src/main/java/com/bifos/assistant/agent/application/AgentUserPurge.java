package com.bifos.assistant.agent.application;

import com.bifos.assistant.agent.application.port.AgentConnectorDetacher;
import com.bifos.assistant.agent.application.port.ProfileProvisioning;
import com.bifos.assistant.agent.application.port.ProfileSkillFiles;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.infra.AgentRepository;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사용자 정리 때 에이전트 쪽을 맡는다(ADR-20261101 / user-purge).
 *
 * <p>그룹 공개 에이전트는 넘기고, 비공개 에이전트는 지운 것으로 적어 에이전트 정리(ADR-20261009 / agent-purge)가 행을 지우게 한다.
 * 모두 부르는 쪽의 정리 트랜잭션 안에서만 돈다. 이미 넘겼거나 지운 것으로 적은 에이전트는 다시 고르지 않으므로 다시 불러도 같다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@Transactional(propagation = Propagation.MANDATORY)
public class AgentUserPurge {

    private final AgentRepository agents;
    private final AgentLifecycleService lifecycle;
    private final AgentConnectorDetacher connectorDetacher;
    private final ProfileProvisioning provisioner;
    private final ProfileSkillFiles skillFiles;


    /** 그 사용자가 주인인 지우지 않은 그룹 공개 에이전트가 있는가. 넘겨받을 관리자가 필요한지 정한다. */
    public boolean ownsGroupAgents(Long userId) {
        return agents.findOwnedForUpdate(userId).stream().anyMatch(agent -> agent.visibility() == AgentVisibility.GROUP);
    }


    /**
     * 그 사용자의 그룹 공개 에이전트를 {@code newOwnerId} 에게 넘긴다. 공개 범위와 켜짐은 그대로 둔다.
     *
     * <p>셸이나 파일 도구가 켜져 있으면 주인을 바꾸지 않고 실패한다(ADR-086). 새 주인의 실행 공간이 옛 에이전트에 열리기 때문이다.
     *
     * @return 넘긴 에이전트 수
     */
    public int transferGroupAgents(Long userId, Long newOwnerId) {
        List<Agent> shared = agents.findOwnedForUpdate(userId).stream()
            .filter(agent -> agent.visibility() == AgentVisibility.GROUP)
            .toList();
        for (Agent agent : shared) {
            lifecycle.requireOwnerChangeSafe(agent.apiBaseUrl(), agent.hermesProfile());
            agent.changeAccess(agent.enabled(), AgentVisibility.GROUP, newOwnerId);
            agents.save(agent);
        }
        return shared.size();
    }


    /**
     * 그 사용자의 비공개 에이전트를 지운 것으로 적는다. 사용자가 지울 때와 같이 바인딩을 떼고 관리형 profile 을 거둔다.
     *
     * @return 지운 것으로 적은 에이전트 수
     */
    public int deletePrivateAgents(Long userId, Instant now) {
        List<Agent> owned = agents.findOwnedForUpdate(userId).stream()
            .filter(agent -> agent.visibility() == AgentVisibility.PRIVATE)
            .toList();
        for (Agent agent : owned) {
            connectorDetacher.detachAll(agent);
            if (agent.profileManaged()) {
                provisioner.deprovision(agent.hermesProfile());
                removeSkillDirectory(agent);
            }
            agent.markDeleted(now);
            agents.save(agent);
        }
        return owned.size();
    }


    private void removeSkillDirectory(Agent agent) {
        try {
            skillFiles.deleteAll(agent.hermesProfile());
        } catch (RuntimeException failure) {
            // 쓰이지 않을 디렉터리가 남을 뿐이라 정리는 멈추지 않는다. 로그에는 profile 이름을 적지 않는다.
            log.warn("정리한 사용자의 에이전트 스킬 디렉터리를 지우지 못했다 agentId={}", agent.id());
        }
    }
}
