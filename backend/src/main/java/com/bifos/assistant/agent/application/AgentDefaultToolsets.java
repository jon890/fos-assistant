package com.bifos.assistant.agent.application;

import com.bifos.assistant.agent.application.dto.PendingToolsetOutcome;
import com.bifos.assistant.agent.application.dto.SandboxReapplyOutcome;
import com.bifos.assistant.agent.application.port.AgentConnectorBindings;
import com.bifos.assistant.agent.application.port.ProfileSkillFiles;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentToolPolicy;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.hermes.application.port.HermesRequestRejected;
import com.bifos.assistant.hermes.application.port.HermesToolsetClient;
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
 * profile 에서만 켠다. 등록되지 않았으면 그 도구를 빼고 나머지만 켜며 경고를 남긴다. local 로 켜면 그 셸이 다른 profile 의
 * {@code .env} 와 커넥터 값 파일에 닿는다(ADR-086, ADR-20261120 / no-local-shell).
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
        if (defaults.isEmpty()) {
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


    /**
     * 기다리던 기본 도구를 지금 켜진 도구에 더해 실행 공간 안에서만 켠다(ADR-20261119 / image-gen-defaults).
     *
     * <p>{@link #apply} 와 달리 실행 공간이 없을 때 다른 도구만 켜는 쓰기를 하지 않고, 이미 켜져 있어도 쓴다. 나머지 기본 도구는 첫 로그인 때 이미 정했고,
     * 여기서는 기다리는 이름만 본다. 지금 켜진 원본 조회 도구는 사람의 저장처럼 남긴다.
     *
     * @param names 켤 도구 이름. 비어 있지 않다
     * @throws ApiException Hermes 가 답하지 않았거나 쓴 도구가 켜지지 않았을 때. 다음 차례에 다시 본다
     */
    public PendingToolsetOutcome enablePending(Agent agent, List<String> names) {
        // Hermes 에 묻기 전에 정한다. Hermes 가 답하지 않아도 기다리지 않을 에이전트는 빠져야 한다.
        if (agent.visibility() == AgentVisibility.GROUP || agent.ownerUserId() == null) {
            return PendingToolsetOutcome.NOT_ALLOWED;
        }
        if (!skillFiles.uploadedRequestingSecrets(agent.hermesProfile()).isEmpty()) {
            return PendingToolsetOutcome.SKILL_REQUESTS_SECRETS;
        }
        List<String> enabled = toolsets.readEnabled(agent.apiBaseUrl(), agent.hermesProfile());
        LinkedHashSet<String> desired = new LinkedHashSet<>(
            desired(
                enabled.stream().filter(AgentToolPolicy::isKnown).toList(),
                names,
                connectorBindings.connectorServers(agent.id())));
        if (enabled.contains(AgentToolPolicy.ATTACHMENT_INSPECTION)) {
            desired.add(AgentToolPolicy.ATTACHMENT_INSPECTION);
        }
        try {
            // 이미 켜져 있어도 한 번은 다시 쓴다. 도구만 켜져 있고 이미지 서비스 설정이 없는 profile 에 설정을 넣으려는 것이다.
            toolsets.writeApiServerInSandbox(agent.hermesProfile(), List.copyOf(desired), agent.sandboxOwner());
        } catch (ApiException rejected) {
            if (rejected.code() != ErrorCode.AGENT_SANDBOX_UNAVAILABLE) {
                throw rejected;
            }
            return PendingToolsetOutcome.SANDBOX_UNAVAILABLE;
        }
        if (!toolsets.readEnabled(agent.apiBaseUrl(), agent.hermesProfile()).containsAll(names)) {
            throw new ApiException(ErrorCode.AGENT_TOOLS_NOT_APPLIED, "Hermes did not apply the pending default toolsets");
        }
        return PendingToolsetOutcome.ENABLED;
    }


    /**
     * 실행 공간 도구가 켜져 있으면 지금 목록 그대로 실행 공간 쓰기로 다시 저장한다(ADR-20261120 / no-local-shell).
     *
     * <p>이미 local 로 켜진 셸을 바로잡는 기동 작업이 부른다. 공개 범위와 올린 스킬로 제외하지 않는다. 그런 에이전트도 이미 셸이
     * 켜져 있다면 local 로 남아서는 안 되기 때문이다. plugin 이 정책에 없는 profile 이라 거절했고 {@code mayRemoveShell} 이
     * 참일 때만 실행 공간 도구를 빼고 다시 쓴다. 정책이 없거나 틀린 거절과 Hermes 실패는 그대로 올려 부르는 쪽이 다음에 다시 보게
     * 한다.
     *
     * @param mayRemoveShell 배포 때 한 번 점검하는 에이전트다. 거짓이면 정책 밖이어도 셸을 끄지 않는다
     * @throws ApiException 정책 밖 거절이 아닌 실패. 아무것도 바꾸지 않았다
     */
    public SandboxReapplyOutcome reapplySandbox(Agent agent, boolean mayRemoveShell) {
        List<String> enabled = toolsets.readEnabled(agent.apiBaseUrl(), agent.hermesProfile());
        if (!AgentToolPolicy.hasSandboxToolset(enabled)) {
            return SandboxReapplyOutcome.NOT_NEEDED;
        }
        LinkedHashSet<String> desired = new LinkedHashSet<>(
            desired(
                enabled.stream().filter(AgentToolPolicy::isKnown).toList(),
                List.of(),
                connectorBindings.connectorServers(agent.id())));
        if (enabled.contains(AgentToolPolicy.ATTACHMENT_INSPECTION)) {
            desired.add(AgentToolPolicy.ATTACHMENT_INSPECTION);
        }
        if (mayRemoveShell && agent.visibility() == AgentVisibility.GROUP && agent.ownerUserId() != null) {
            // 옛 데이터로 셸이 켜진 그룹 공개 에이전트다. 실행 공간이 주인의 것이라 그룹 사용자가 주인의 파일을 본다.
            return removeShell(agent, desired, "그룹 공개 에이전트의 실행 공간이 주인 공간이라");
        }
        try {
            toolsets.writeApiServerInSandbox(agent.hermesProfile(), List.copyOf(desired), agent.sandboxOwner());
            return SandboxReapplyOutcome.REWRITTEN;
        } catch (HermesRequestRejected rejected) {
            if (!rejected.profileUnlisted()) {
                throw rejected;
            }
        }
        if (!mayRemoveShell) {
            log.warn(
                "실행 공간 정책에 없는 profile 이지만 이미 점검한 에이전트라 셸을 끄지 않았다. 정책을 확인한다 agent={} profile={}",
                agent.code(),
                agent.hermesProfile());
            return SandboxReapplyOutcome.UNLISTED_KEPT;
        }
        return removeShell(agent, desired, "실행 공간 정책에 없는 profile 이라");
    }


    private SandboxReapplyOutcome removeShell(Agent agent, Set<String> desired, String reason) {
        toolsets.writeApiServer(
            agent.hermesProfile(), AgentToolPolicy.withoutSandboxToolsets(List.copyOf(desired)), agent.sandboxOwner());
        log.warn("{} 셸·파일·코드 실행·사진 도구를 껐다 agent={} profile={}", reason, agent.code(), agent.hermesProfile());
        return SandboxReapplyOutcome.SHELL_REMOVED;
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
        // 지금 켜진 것은 그대로 둔다. 그 안에 셸 계열이 있으면 plugin 이 정책 밖 저장을 거절하므로 쓰지 않는다(ADR-20261120 / no-local-shell).
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
