package com.bifos.assistant.agent.application.toolset;

import com.bifos.assistant.agent.application.AgentConnectorBindings;
import com.bifos.assistant.agent.application.ProfileSkillFiles;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentToolPolicy;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.hermes.HermesToolsetClient;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 첫 로그인에 만든 기본 에이전트에 운영이 정한 기본 도구를 켠다(ADR-20261008-default-toolsets).
 *
 * <p>사람이 고르지 않은 도구라 등급 판정 대신 운영 설정을 허락으로 본다. 대신 셸·파일·사진 도구는 실행 공간 정책에 등록된
 * profile 에서만 켠다. 등록되지 않았으면 그 도구를 빼고 나머지만 켜며 경고를 남긴다. 사람의 도구 저장처럼 local 로 켜면
 * 그 셸이 다른 profile 의 {@code .env} 와 커넥터 값 파일에 닿는다(ADR-086).
 *
 * <p>실패해도 예외를 올리지 않는다. 로그인은 이미 끝났고, 도구는 관리자가 에이전트 도구 화면에서 다시 켤 수 있다.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AgentDefaultToolsets {

    private final HermesToolsetClient toolsets;
    private final ProfileSkillFiles skillFiles;
    private final AgentConnectorBindings connectorBindings;

    /**
     * 지금 켜진 도구에 기본 도구를 더해 쓴다.
     *
     * @return Hermes 에 쓴 목록. 쓸 것이 없었거나 쓰지 못했으면 빈 목록이다
     */
    public List<String> apply(Agent agent, List<String> defaults) {
        if (defaults.isEmpty() || agent.connectorManaged()) {
            return List.of();
        }
        try {
            List<String> written = write(agent, defaults);
            if (!written.isEmpty()) {
                warnIfNotApplied(agent, written);
            }
            return written;
        } catch (RuntimeException failure) {
            log.warn(
                    "기본 도구를 켜지 못했다. 관리자가 에이전트 도구 화면에서 켜야 한다 agent={} profile={}",
                    agent.code(),
                    agent.hermesProfile(),
                    failure);
            return List.of();
        }
    }

    private List<String> write(Agent agent, List<String> defaults) {
        List<String> current = toolsets.readEnabled(agent.apiBaseUrl(), agent.hermesProfile()).stream()
                .filter(AgentToolPolicy::isKnown)
                .toList();
        List<String> wanted = allowedFor(agent, defaults);
        if (current.containsAll(wanted)) {
            return List.of();
        }
        Set<String> connectorServers = connectorBindings.connectorServers(agent.id());
        List<String> desired = desired(current, wanted, connectorServers);
        if (!AgentToolPolicy.hasSandboxToolset(desired)) {
            toolsets.writeApiServer(agent.hermesProfile(), desired, agent.sandboxOwner());
            return desired;
        }
        try {
            toolsets.writeApiServerInSandbox(agent.hermesProfile(), desired, agent.sandboxOwner());
            return desired;
        } catch (ApiException rejected) {
            if (rejected.code() != ErrorCode.AGENT_SANDBOX_UNAVAILABLE) {
                throw rejected;
            }
            log.warn(
                    "실행 공간을 쓸 수 없어 기본 도구에서 셸·파일·사진 도구를 뺐다. 정책 등록을 확인한 뒤 관리자가 켠다 agent={} profile={}",
                    agent.code(),
                    agent.hermesProfile());
        }
        List<String> fallbackWanted = AgentToolPolicy.withoutSandboxToolsets(wanted);
        if (current.containsAll(fallbackWanted)) {
            return List.of();
        }
        // 지금 켜진 것은 그대로 둔다. 그 안에 셸 계열이 있으면 이 쓰기가 미등록 profile 의 셸을 local 로 바꾸므로 쓰지 않는다.
        List<String> fallback = desired(current, fallbackWanted, connectorServers);
        if (AgentToolPolicy.hasSandboxToolset(fallback)) {
            log.warn(
                    "셸 계열이 이미 켜진 profile 이라 실행 공간 없이 기본 도구를 쓰지 않았다 agent={} profile={}",
                    agent.code(),
                    agent.hermesProfile());
            return List.of();
        }
        toolsets.writeApiServer(agent.hermesProfile(), fallback, agent.sandboxOwner());
        return fallback;
    }

    /** 쓴 내장 도구가 실제 API 실행에 켜졌는지 본다. profile 설정이 막은 도구는 여기서 드러난다. */
    private void warnIfNotApplied(Agent agent, List<String> written) {
        List<String> applied = toolsets.readEnabled(agent.apiBaseUrl(), agent.hermesProfile());
        List<String> missing = written.stream()
                .filter(AgentToolPolicy::isKnown)
                .filter(name -> !applied.contains(name))
                .toList();
        if (!missing.isEmpty()) {
            log.warn(
                    "기본 도구 가운데 켜지지 않은 것이 있다 agent={} profile={} missing={}",
                    agent.code(),
                    agent.hermesProfile(),
                    missing);
        }
    }

    /** 공개 범위와 올린 스킬 때문에 켤 수 없는 기본 도구를 빼고 남은 것이다. */
    private List<String> allowedFor(Agent agent, List<String> defaults) {
        List<String> result = defaults;
        if (agent.visibility() == AgentVisibility.GROUP) {
            result = result.stream()
                    .filter(name -> !AgentToolPolicy.requiresPrivate(name))
                    .toList();
        }
        // 셸 도구가 켜지면 Hermes 가 스킬이 요청한 profile 의 환경 값과 파일을 실행 공간에 넣는다(ADR-086).
        if (AgentToolPolicy.hasSandboxToolset(result)
                && !skillFiles.uploadedRequestingSecrets(agent.hermesProfile()).isEmpty()) {
            log.warn(
                    "비밀 요청 칸이 있는 올린 스킬이 있어 기본 도구에서 셸·파일·사진 도구를 뺐다 agent={} profile={}",
                    agent.code(),
                    agent.hermesProfile());
            result = AgentToolPolicy.withoutSandboxToolsets(result);
        }
        return result;
    }

    /** 대시보드는 목록을 통째로 바꾸므로 지금 켜진 것과 Control Plane MCP, 붙은 커넥터 서버를 함께 보낸다(ADR-083). */
    private static List<String> desired(List<String> current, List<String> wanted, Set<String> connectorServers) {
        LinkedHashSet<String> result = new LinkedHashSet<>(current);
        result.addAll(wanted);
        result.add(AgentToolPolicy.CONTROL_PLANE_MCP);
        result.addAll(connectorServers);
        return List.copyOf(result);
    }
}
