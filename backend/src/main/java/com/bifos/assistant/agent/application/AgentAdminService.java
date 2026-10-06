package com.bifos.assistant.agent.application;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자가 에이전트를 등록하고 고치는 유스케이스다.
 *
 * <p>관리자인지는 부르는 쪽이 먼저 확인한다. 잠금 조회와 닿는지 확인과 저장이 한 트랜잭션 안에서 돈다.
 */
@Service
@RequiredArgsConstructor
public class AgentAdminService {
    private final AgentRepository agents;
    private final AppUserRepository users;
    private final AgentLifecycleService lifecycle;
    private final AgentEndpointProbe endpointProbe;
    private final KnownFlows flows;
    private final AgentConnectorBindings connectorBindings;
    private final Clock clock;

    @Transactional
    public Agent create(AgentCreateCommand command) {
        if (agents.findByCode(command.code()).isPresent()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "this agent code is already used");
        }
        Long ownerId = ownerId(command.visibility(), command.ownerEmail());
        // 잘못된 주소나 profile 로 등록하면 그 에이전트의 모든 대화가 실패한다. 저장하기 전에 닿는지 본다.
        endpointProbe.requireReachable(command.apiBaseUrl(), command.hermesProfile());
        if (command.visibility() == AgentVisibility.GROUP) {
            lifecycle.requireGroupSafe(command.apiBaseUrl(), command.hermesProfile());
        }
        Agent agent = Agent.of(
                command.code(),
                command.name(),
                command.hermesProfile(),
                command.apiBaseUrl(),
                command.costMode(),
                command.credentialScope(),
                command.visibility(),
                ownerId,
                clock.instant());
        agent.assignFlow(requireKnownFlow(command.flow()));
        return agents.save(agent);
    }

    /** 관리 목록에 보일 에이전트다. 지운 에이전트는 되살리지 못하므로 두지 않는다. */
    public List<Agent> list() {
        return agents.findAll().stream().filter(agent -> !agent.isDeleted()).toList();
    }

    /**
     * 관리자가 에이전트의 사용 여부와 공개 범위, 주인, 주소를 고친다.
     *
     * <p>연결이 붙은 에이전트는 그룹으로 바꾸지 못하고 주인도 바꾸지 못한다(ADR-083). 주인을 바꾸면 남의 값이 든 profile 이 새
     * 주인에게 넘어가고, 새 주인은 남의 연결이라 떼지도 못한다. 바인딩은 에이전트 행을 잠근 뒤에 읽는다. 잠금은 경합하면 곧바로
     * {@code AGENT_BUSY} 로 거절하므로 붙이기가 잠금을 쥔 동안에는 이 수정이 거절되고, 이 수정이 먼저 잠그면 붙이기가 기다렸다가
     * 이 커밋을 보고 판정한다. 트랜잭션의 첫 읽기가 이 잠금 읽기라 뒤의 바인딩 조회는 잠금을 얻기 전에 커밋된 바인딩을 본다.
     */
    @Transactional
    public Agent update(String code, AgentUpdateCommand command) {
        Agent agent = requireAgentForUpdate(code);
        if (agent.connectorManaged()) {
            throw new ApiException(ErrorCode.FORBIDDEN, "connector-managed agents cannot be edited here");
        }
        // 주인은 공개 범위와 별개다(ADR-033). 새 주인을 주지 않으면 그룹으로 바꿔도 지금 주인이 남는다.
        Long ownerId = command.ownerEmail() == null || command.ownerEmail().isBlank()
                ? agent.ownerUserId()
                : ownerId(command.visibility(), command.ownerEmail());
        if (command.visibility() == AgentVisibility.PRIVATE && ownerId == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "a private agent needs an owner");
        }
        boolean ownerChanges = !Objects.equals(ownerId, agent.ownerUserId());
        if ((ownerChanges || command.visibility() == AgentVisibility.GROUP)
                && connectorBindings.hasBindings(agent.id())) {
            if (ownerChanges) {
                throw new ApiException(
                        ErrorCode.AGENT_HAS_CONNECTIONS, "detach the connections before changing the owner");
            }
            throw new ApiException(
                    ErrorCode.AGENT_CONNECTIONS_REQUIRE_PRIVATE, "an agent with connections must stay private");
        }
        String apiBaseUrl = effectiveApiBaseUrl(agent, command.apiBaseUrl());
        if (command.enabled() && command.visibility() == AgentVisibility.GROUP) {
            lifecycle.requireGroupSafe(apiBaseUrl, agent.hermesProfile());
        }
        // 셸이나 파일 도구가 켜진 profile 은 실행 공간이 지금 주인의 디렉터리를 가리킨다(ADR-086). 주인이 바뀔 때만 본다.
        if (!Objects.equals(ownerId, agent.ownerUserId())) {
            lifecycle.requireOwnerChangeSafe(apiBaseUrl, agent.hermesProfile());
        }
        agent.changeAccess(command.enabled(), command.visibility(), ownerId);
        if (!apiBaseUrl.equals(agent.apiBaseUrl())) {
            agent.changeApiBaseUrl(apiBaseUrl);
        }
        // 비어 있으면 그대로 둔다. 주소나 사용 여부만 고치는 요청이 이 설정을 끄지 않게 한다.
        if (command.proactiveCheckWritesAllowed() != null) {
            agent.changeProactiveCheckWritesAllowed(command.proactiveCheckWritesAllowed());
        }
        return agents.save(agent);
    }

    /**
     * 모르는 흐름 이름을 저장하지 못하게 막는다.
     *
     * <p>기동할 때도 같은 것을 확인한다. 여기서 막는 것은 이미 도는 서버를 다음 기동에서 세우지 않기
     * 위해서다.
     */
    private String requireKnownFlow(String flow) {
        if (flow == null || flow.isBlank() || flows.known(flow)) {
            return flow;
        }
        throw new ApiException(ErrorCode.VALIDATION_FAILED, "no such flow");
    }

    /**
     * 주소를 바꾸는 요청이면 닿는지 본 뒤에 바꾼다.
     *
     * <p>비어 있거나 지금 값과 같으면 아무것도 하지 않는다. 다른 것만 고치는 요청이 주소를 지우거나
     * 쓸데없이 Hermes 를 부르지 않게 한다. 끝의 {@code /} 만 다른 것도 같은 값으로 본다.
     */
    private String effectiveApiBaseUrl(Agent agent, String apiBaseUrl) {
        if (apiBaseUrl == null || apiBaseUrl.isBlank()) {
            return agent.apiBaseUrl();
        }
        String next = stripTrailingSlash(apiBaseUrl.strip());
        if (next.equals(agent.apiBaseUrl())) {
            return agent.apiBaseUrl();
        }
        endpointProbe.requireReachable(next, agent.hermesProfile());
        return next;
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    /**
     * 고칠 에이전트를 잠그고 읽는다. 지운 에이전트는 없는 에이전트와 같다.
     *
     * <p>여기서 막지 않으면 관리자가 {@code enabled=true} 로 지운 에이전트를 되살린다. 그 profile 은 이미
     * 거둬졌을 수 있다.
     */
    private Agent requireAgentForUpdate(String code) {
        Agent agent = agents.findByCodeForUpdate(code)
                .orElseThrow(() -> new ApiException(ErrorCode.AGENT_NOT_FOUND, "no such agent"));
        if (agent.isDeleted()) {
            throw new ApiException(ErrorCode.AGENT_NOT_FOUND, "no such agent");
        }
        return agent;
    }

    /**
     * 받은 메일 주소의 사용자를 주인으로 고른다.
     *
     * <p>비어 있으면 자기만 보는 에이전트는 거절하고 그룹 공개 에이전트는 주인 없이 둔다. 없는 사용자면
     * 공개 범위와 무관하게 거절한다.
     */
    private Long ownerId(AgentVisibility visibility, String ownerEmail) {
        if (ownerEmail == null || ownerEmail.isBlank()) {
            if (visibility == AgentVisibility.PRIVATE) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "a private agent needs an owner");
            }
            return null;
        }
        return users.findByEmail(ownerEmail)
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED, "no such user"))
                .id();
    }
}
