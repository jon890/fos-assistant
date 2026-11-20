package com.bifos.assistant.connector.connection.application;

import com.bifos.assistant.connector.approval.application.ConnectorActionService;
import com.bifos.assistant.agent.application.port.AgentConnectorDetacher;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.connector.connection.application.dto.AgentConnectionView;
import com.bifos.assistant.connector.connection.application.dto.ConnectorBindingAttempt;
import com.bifos.assistant.connector.connection.application.dto.BoundAgentSummary;
import com.bifos.assistant.connector.connection.application.dto.AgentConnectionsView;
import com.bifos.assistant.connector.connection.application.dto.ConnectorOperationFailure;
import com.bifos.assistant.connector.connection.application.dto.ResyncOutcome;
import com.bifos.assistant.connector.connection.domain.ConnectorBinding;
import com.bifos.assistant.connector.connection.domain.ConnectorConnection;
import com.bifos.assistant.connector.connection.domain.type.BindingStatus;
import com.bifos.assistant.connector.connection.domain.type.ConnectionStatus;
import com.bifos.assistant.connector.connection.infra.ConnectorBindingRepository;
import com.bifos.assistant.connector.connection.infra.ConnectorConnectionRepository;
import com.bifos.assistant.hermes.application.port.ConnectorInstallConflict;
import com.bifos.assistant.hermes.application.port.ConnectorProfileRejected;
import com.bifos.assistant.hermes.application.port.ConnectorSandboxUnavailable;
import com.bifos.assistant.hermes.application.port.HermesConnectorClient;
import com.bifos.assistant.hermes.application.port.HermesSkillClient.HermesSkill;
import com.bifos.assistant.hermes.application.dto.ConnectorManifest;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.skill.library.infra.SkillPublisher;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 연결을 에이전트에 붙이고 떼고, 붙인 바인딩을 그 profile 의 설치와 맞춘다(ADR-083).
 *
 * <p>붙이고 떼는 사람은 그 에이전트의 주인이고 자기 연결만 붙인다. 관리자도 남의 에이전트에는 붙이거나 떼지 못한다.
 * 재시작 없이 반영될 설치는 반영 예정 시각이 지나면 {@link ConnectorBindingApplier#applyDue()} 가 스스로
 * 확인한다(ADR-20261007 / connector-live-reload). 관리자는 재시작이 필요한 바인딩만 공유 gateway 를 재시작한 뒤 반영 완료를 누른다. 붙이기와 떼기,
 * 반영 완료, 예약 확인은 사용자 행을 먼저, 에이전트 행을 다음에 잠근다. 공개 범위 변경과 관리자 수정도 같은 에이전트 행을
 * 잠그므로 동시에 와도 한쪽이 다른 쪽의 커밋을 보고 판정한다.
 *
 * <p>설치 외부 호출은 시작 상태를 커밋한 뒤 트랜잭션 밖에서 한다. 순서와 실패 처리는
 * {@code docs/features/connector.md}가 갖는다.
 *
 * <p>{@link #reinstall}, {@link #detach}, {@link #resync} 는 연결 서비스가 자기 트랜잭션 안에서 부르는 단계다. 프록시를
 * 거쳐 부르므로 public 으로 둔다.
 */
@Slf4j
@Service
public class ConnectorBindingService implements AgentConnectorDetacher {
    private static final String STEP_DETACH = "detach";

    private final ConnectorBindingRepository bindings;
    private final ConnectorConnectionRepository connections;
    private final AgentRepository agents;
    private final AppUserRepository users;
    private final HermesConnectorClient connector;
    private final SkillPublisher skills;
    private final ConnectorActionService approvals;
    private final ConnectorInstallAttempts attempts;
    private final Clock clock;


    /**
     * 반영 완료의 트랜잭션이 본 결과다. 확인하지 못한 상태도 커밋한 뒤에 실패를 알리려고 값으로 돌려준다.
     *
     * @param attempt 결과 트랜잭션에서 대조할 설치 입력
     * @param manifest 응답 화면을 만드는 선언. 카탈로그에서 빠졌으면 null
     */
    private record Confirmed(ConnectorBindingAttempt attempt, ConnectorManifest manifest) {
    }

    /** 이미 붙은 바인딩이면 view만 있고, 새 붙이기면 커밋한 설치 입력이 있다. */
    private record Bound(AgentConnectionView view, ConnectorBindingAttempt attempt) {
    }


    // 설치 경계와 시계는 협력 객체와 주입된 Clock이 갖는다.
    @Autowired
    public ConnectorBindingService(
        ConnectorBindingRepository bindings,
        ConnectorConnectionRepository connections,
        AgentRepository agents,
        AppUserRepository users,
        HermesConnectorClient connector,
        SkillPublisher skills,
        ConnectorActionService approvals,
        ConnectorInstallAttempts attempts,
        Clock clock) {
        this.bindings = bindings;
        this.connections = connections;
        this.agents = agents;
        this.users = users;
        this.connector = connector;
        this.skills = skills;
        this.approvals = approvals;
        this.attempts = attempts;
        this.clock = clock;
    }


    /**
     * 그 에이전트에서 본 내 연결들이다. 해제한 연결은 빠진다. 주인만 읽는다.
     *
     * <p>붙일 수 없는 에이전트면 까닭을 함께 준다. 화면이 붙이기 단추를 막고 안내한다.
     */
    @Transactional(readOnly = true)
    public AgentConnectionsView listForAgent(CurrentUser user, String agentCode) {
        Agent agent = requireOwned(user, agentCode, false);
        Map<String, ConnectorManifest> manifests = ConnectorManifests.read(connector).stream()
            .collect(Collectors.toMap(ConnectorManifest::id, Function.identity()));
        Map<Long, ConnectorBinding> bound = bindings.findByAgentId(agent.id()).stream()
            .collect(Collectors.toMap(binding -> binding.connection().id(), Function.identity()));
        List<AgentConnectionView> views = connections.findByUserId(user.id()).stream()
            .filter(connection -> connection.status() != ConnectionStatus.DISCONNECTED)
            .sorted(Comparator.comparing(ConnectorConnection::connectorId))
            .map(connection -> view(connection, bound.get(connection.id()), manifests.get(connection.connectorId())))
            .toList();
        return new AgentConnectionsView(views, blockedReason(agent));
    }


    /**
     * 내 연결을 내 에이전트에 붙인다. 이미 붙어 있으면 지금 상태를 돌려준다.
     *
     * <p>붙인 바인딩은 늘 {@code PENDING} 이다. 대시보드가 {@code reload_pending} 으로 답하면 공유 gateway 의 MCP 설정
     * 맞추기 주기가 새 서버를 연결하므로 반영 예정 시각을 적고, 그 시각이 지나면 {@link ConnectorBindingApplier#applyDue()}
     * 가 {@code READY} 로 바꾼다(ADR-20261007 / connector-live-reload). 재시작이 필요하다고 답하면 재시작 대기로 두고 관리자 반영 완료가 바꾼다.
     * {@code skills} toolset 은 켜지 않는다. 도구 선택은 주인이 화면에서 정하고 (ADR-029) 붙이기가 다른 도구를 몰래 켜지
     * 않는다.
     *
     * <p>대시보드가 409 나 401 로 거절하면 대시보드는 아무것도 바꾸지 않았고, 결과 트랜잭션이 현재 시도의 바인딩 행만 지운다.
     * 그 밖의 외부 실패는 바인딩을 {@code PENDING} 으로 남기고 연결 실패로 끝낸다. 대시보드가 반쯤 반영했을 수 있어 다음 연결
     * 확인이 그 바인딩의 설치를 다시 보낸다.
     *
     * <p>커넥터가 {@code single_binding} 을 선언했고 그 연결이 이미 다른 에이전트에 붙어 있으면 설치 전에 거절한다.
     * 대시보드가 409 {@code sandbox_unavailable} 로 거절하면 실행 공간이 없다는 다른 오류로 옮긴다.
     */
    public AgentConnectionView bind(CurrentUser user, String agentCode, String connectorId) {
        Optional<ConnectorManifest> manifest = ConnectorManifests.find(connector, connectorId);
        Agent found = requireOwned(user, agentCode, false);
        String profile = found.hermesProfile();
        Set<String> taken = manifest.filter(declared -> !declared.skills().isEmpty())
            .map(declared -> skills.list(profile).stream().map(HermesSkill::name).collect(Collectors.toSet()))
            .orElse(Set.of());
        Bound started = attempts.start(() -> bindLocked(user, agentCode, connectorId, manifest, profile, taken));
        if (started.attempt() == null) {
            return started.view();
        }
        ConnectorBindingAttempt attempt = started.attempt();
        Optional<ResyncOutcome> outcome;
        try {
            outcome = attempts.execute(attempt);
        } catch (ConnectorSandboxUnavailable ex) {
            if (attempts.reject(attempt)) {
                throw new ApiException(ErrorCode.AGENT_SANDBOX_UNAVAILABLE,
                    "the agent profile has no isolated workspace for this connector");
            }
            outcome = Optional.empty();
        } catch (ConnectorInstallConflict ex) {
            if (attempts.reject(attempt)) {
                throw new ApiException(ErrorCode.CONNECTOR_BIND_CONFLICT, "the agent profile conflicts with this connector");
            }
            outcome = Optional.empty();
        } catch (ConnectorProfileRejected ex) {
            if (attempts.reject(attempt)) {
                throw new ApiException(ErrorCode.CONNECTOR_PROFILE_NOT_READY, "the agent profile does not accept connectors yet");
            }
            outcome = Optional.empty();
        }
        if (outcome.orElse(null) == ResyncOutcome.CALL_FAILED) {
            throw new ConnectorOperationFailure();
        }
        return currentView(user.id(), attempt.agentId(), connectorId, manifest.orElse(null));
    }


    /** 소유자와 에이전트를 잠근 뒤 새 바인딩과 설치 입력을 함께 커밋한다. */
    private Bound bindLocked(CurrentUser user, String agentCode, String connectorId,
        Optional<ConnectorManifest> manifest, String profile, Set<String> taken) {
        lockUser(user.id());
        Agent agent = requireOwned(user, agentCode, true);
        if (agent.visibility() != AgentVisibility.PRIVATE) {
            throw new ApiException(
                ErrorCode.AGENT_CONNECTIONS_REQUIRE_PRIVATE, "connections can be attached to a private agent only");
        }
        ConnectorConnection connection = connections
            .findByUserIdAndConnectorId(user.id(), connectorId)
            .filter(found -> found.status() == ConnectionStatus.READY && found.vaultStored())
            .orElseThrow(() -> new ApiException(ErrorCode.CONNECTOR_NOT_CONNECTED, "connect this connector first"));
        Optional<ConnectorBinding> existing = bindings.findByAgentIdAndConnectionId(agent.id(), connection.id());
        if (existing.isPresent()) {
            return new Bound(view(connection, existing.get(), manifest.orElse(null)), null);
        }
        ConnectorManifest declared = manifest.orElseThrow(ConnectorErrors::notFound);
        if (declared.singleBinding()
            && !bindings.findByConnectionId(connection.id()).isEmpty()) {
            throw new ApiException(ErrorCode.CONNECTOR_SINGLE_BINDING, "this connection is attached to another agent");
        }
        if (!Objects.equals(agent.hermesProfile(), profile)) {
            throw new ApiException(ErrorCode.AGENT_BUSY, "the agent profile changed while preparing");
        }
        if (declared.skills().stream().anyMatch(taken::contains)) {
            throw new ApiException(ErrorCode.SKILL_NAME_TAKEN, "the agent already has a skill named like a skill of this connector");
        }
        Instant now = now();
        ConnectorBinding binding = bindings.save(ConnectorBinding.pending(agent, connection, declared.mcpServer(), now));
        return new Bound(null, attempts.begin(binding, manifest, false, false, false, now));
    }


    /**
     * 내 에이전트에서 연결을 뗀다. 붙어 있지 않으면 아무것도 하지 않는다.
     *
     * <p>그 에이전트가 판정한 승인 줄만 끝낸다. 상시 허락은 사용자와 커넥터에 묶여 다른 에이전트의 바인딩에도 걸리므로 두고,
     * 떼기는 도구 목록에서 이름을 빼므로 재시작을 기다리지 않는다.
     */
    @Transactional
    public void unbind(CurrentUser user, String agentCode, String connectorId) {
        lockUser(user.id());
        Agent agent = requireOwned(user, agentCode, true);
        Optional<ConnectorConnection> connection = connections.findByUserIdAndConnectorId(user.id(), connectorId);
        Optional<ConnectorBinding> binding = connection.flatMap(found -> bindings.findByAgentIdAndConnectionId(agent.id(), found.id()));
        if (binding.isEmpty()) {
            return;
        }
        approvals.rejectPendingFor(connection.get(), agent.id(), now());
        try {
            detach(binding.get());
        } catch (RuntimeException ex) {
            ConnectorBindingInstalls.warn(STEP_DETACH, connectorId, ex);
            throw new ConnectorOperationFailure();
        }
    }


    /**
     * 관리자가 공유 gateway 를 재시작한 뒤 누르는 반영 완료다. 그 에이전트의 주인이 같은 그룹이어야 한다.
     *
     * <p>{@code shownSince} 는 관리자 목록에 보였던 그 바인딩의 재시작 대기 시작 시각이다. 바인딩의 값이 그보다 늦으면 관리자가
     * 재시작한 뒤에 다시 설치된 것이라 대기를 풀지 않고 거절한다. 두 값이 모두 비었으면 같은 값으로 본다. 마이그레이션이 채우지
     * 못한 옛 바인딩이다. 같으면 설치를 한 번 다시 보내 반영됐는지 본다.
     *
     * <p>반영을 확인한 때만 대기를 푼다. 설치를 다시 보냈는데 configured 나 probe 가 실패하면 {@code PENDING} 과 재시작
     * 대기로 남고, 그 상태를 커밋한 뒤 연결 실패로 끝낸다.
     *
     * <p>붙이기와 같은 차례로 주인의 사용자 행 다음에 에이전트 행을 잠근다. 에이전트 id 와 주인 id 는 트랜잭션 밖에서 읽고,
     * 트랜잭션의 첫 문장이 주인의 사용자 행 잠금이다. MySQL 의 REPEATABLE READ 는 첫 일반 읽기에서 읽기 시점을 정하므로,
     * 잠금 없는 읽기가 먼저 오면 사용자 행 잠금을 기다린 뒤에도 등록이 커밋한 새 재시작 대기 시각을 보지 못한다. 잠근 뒤 주인이
     * 바뀌었으면 {@code AGENT_BUSY} 다. 그다음 지금 주인의 그룹이 관리자와 다르면 {@code AGENT_NOT_FOUND} 다.
     */
    public AgentConnectionView confirmApplied(
        CurrentUser admin, String agentCode, String connectorId, Instant shownSince) {
        if (!admin.isAdmin()) {
            throw new ApiException(ErrorCode.FORBIDDEN, "administrator access is required");
        }
        Agent found = agents.findByCode(agentCode)
            .filter(agent -> !agent.isDeleted())
            .orElseThrow(ConnectorBindingService::agentNotFound);
        Long agentId = found.id();
        Long ownerId = found.ownerUserId();
        if (ownerId == null) {
            // 주인 없는 에이전트에는 바인딩이 없다. 다른 그룹 관리자에게 그 에이전트가 있는지 드러내지 않게 같은 404 로 답한다.
            throw agentNotFound();
        }
        Optional<ConnectorManifest> manifest = ConnectorManifests.find(connector, connectorId);
        Confirmed confirmed = attempts.start(() -> confirmLocked(admin, agentId, ownerId, connectorId, shownSince, manifest));
        if (confirmed == null) {
            throw new ConnectorOperationFailure();
        }
        Optional<ResyncOutcome> outcome = attempts.execute(confirmed.attempt());
        AgentConnectionView view = currentView(ownerId, agentId, connectorId, confirmed.manifest());
        if (outcome.isPresent() && (outcome.get() != ResyncOutcome.READY || view.status() != BindingStatus.READY)) {
            throw ConnectorErrors.notApplied(outcome.get());
        }
        return view;
    }


    /**
     * 반영 완료의 트랜잭션이다. 트랜잭션 안에서만 부른다.
     *
     * @return 결과 트랜잭션에서 대조할 설치 입력과 manifest
     */
    private Confirmed confirmLocked(
        CurrentUser admin, Long agentId, Long ownerId, String connectorId, Instant shownSince, Optional<ConnectorManifest> manifest) {
        AppUser owner = users.findByIdForUpdate(ownerId).orElse(null);
        Agent agent = agents.findByIdForUpdate(agentId)
            .filter(candidate -> !candidate.isDeleted())
            .orElseThrow(ConnectorBindingService::agentNotFound);
        if (!Objects.equals(agent.ownerUserId(), ownerId)) {
            // 주인을 읽은 뒤 바뀌었다. 잠근 사용자 행이 지금 주인의 것이 아니다.
            throw new ApiException(ErrorCode.AGENT_BUSY, "the agent owner changed while confirming");
        }
        // 그룹은 잠근 뒤의 지금 주인으로 본다. 잠그기 전에 보면 주인이 바뀐 요청도 옛 주인의 그룹으로 거절된다.
        // 다른 그룹의 에이전트는 없는 것으로 답한다. 403 과 404 가 갈리면 다른 그룹의 에이전트 코드가 있는지 드러난다.
        if (owner == null || !owner.groupId().equals(admin.groupId())) {
            throw agentNotFound();
        }
        attempts.lockOwner(ownerId);
        ConnectorConnection connection = connections
            .findByUserIdAndConnectorId(ownerId, connectorId)
            .orElseThrow(ConnectorBindingService::notBound);
        ConnectorBinding binding = bindings.findByAgentIdAndConnectionId(agent.id(), connection.id())
            .orElseThrow(ConnectorBindingService::notBound);
        Instant since = binding.restartRequiredSince();
        if (since != null && (shownSince == null || since.isAfter(shownSince))) {
            throw new ApiException(
                ErrorCode.CONNECTOR_RESTART_AGAIN, "the connection was installed again after the restart");
        }
        return new Confirmed(attempts.begin(binding, manifest, true, true, false, now()), manifest.orElse(null));
    }


    /**
     * 그 에이전트의 바인딩을 모두 뗀다.
     *
     * <p>profile 에서 떼고 행을 지운다. 뒤의 삭제가 실패해 트랜잭션이 되돌려지면 행은 남고 profile 에서는 떼어진 상태다.
     * 다음 연결 확인이 설치가 configured 가 아닌 것을 보고 그 바인딩을 {@code PENDING} 으로 둔다.
     */
    @Override
    @Transactional
    public void detachAll(Agent agent) {
        for (ConnectorBinding binding : bindings.findByAgentId(agent.id())) {
            ConnectorConnection connection = binding.connection();
            String connectorId = connection.connectorId();
            approvals.rejectPendingFor(connection, agent.id(), now());
            try {
                detach(binding);
            } catch (RuntimeException ex) {
                ConnectorBindingInstalls.warn(STEP_DETACH, connectorId, ex);
                throw new ConnectorOperationFailure();
            }
        }
    }


    /**
     * 값을 바꾼 연결의 바인딩에 다시 보낼 설치를 준비한다. 연결 등록의 시작 트랜잭션 안에서 부른다.
     *
     * <p>대시보드가 보관 파일에서 값을 다시 복사한다. 떠 있는 MCP 프로세스는 옛 값을 쥐고 있으므로 결과 단계에서 재시작 필요를 누적하고 {@code PENDING} 으로 둔다.
     *
     * @return 시작 트랜잭션이 커밋할 불변 설치 입력
     */
    public ConnectorBindingAttempt reinstall(ConnectorBinding binding, ConnectorManifest manifest) {
        return attempts.begin(binding, Optional.of(manifest), false, false, true, now());
    }


    List<BoundAgentSummary> boundAgents(ConnectorConnection connection) {
        return bindings.findByConnectionId(connection.id()).stream()
            .map(BoundAgentSummary::from)
            .toList();
    }


    /**
     * 바인딩을 profile 에서 떼고 행을 지운다. 외부 호출이 실패하면 예외를 그대로 던지고 행은 남는다.
     *
     * <p>카탈로그에서 빠진 커넥터도 대시보드가 소유 기록으로 서버와 env를 지운다.
     */
    public void detach(ConnectorBinding binding) {
        Agent agent = binding.agent();
        String profile = agent.hermesProfile();
        String connectorId = binding.connection().connectorId();
        connector.unbindConnector(profile, connectorId);
        bindings.delete(binding);
    }


    /**
     * 설치를 한 번 다시 보내고 그 profile 에 반영됐는지 본다. 쓸 수 있으면 {@code READY}, 아니면 {@code PENDING} 이다.
     *
     * <p>사용자의 연결 확인과 예약 확인({@code afterRestart} 거짓)에서는 재시작 대기인 바인딩과 반영 예정 시각이 아직 오지
     * 않은 바인딩에 설치를 다시 보내지 않는다. 관리자 반영 완료({@code afterRestart} 참)는 재시작이 끝났다고 보고 다시 보낸다. 다만 반영 예정 시각 전에는 관리자 반영 완료도 다시 보내지 않는다.
     * 다시 보낸 설치가 재시작을 요구하면 그 시각으로 대기를 새로 시작하고, {@code reload_pending} 이면 반영 예정 시각을 새로
     * 적는다. 둘 다 {@code PENDING} 으로 돌아간다. 카탈로그에서 빠진 커넥터는 서버를 확인할 수 없어 다시 보내지 않고
     * {@code PENDING} 이다.
     *
     * <p>바인딩은 설치가 켜져 있고 configured 이며 정책 hook 이 켜져 있고 바인딩 방식이며 probe 가 도구를 낼 때 쓸 수 있다.
     * 켜진 내장 도구는 보지 않는다. 그 에이전트의 도구는 주인이 정한다. 바인딩 설치는 바뀐 것이 있을 때만 재시작이 필요하다고
     * 답한다.
     *
     * @return 시작 트랜잭션이 커밋할 불변 설치 입력. 외부 호출과 결과 대조는 {@link #execute}가 한다
     */
    public ConnectorBindingAttempt resync(ConnectorBinding binding, Optional<ConnectorManifest> manifest, boolean afterRestart) {
        return attempts.begin(binding, manifest, true, afterRestart, false, now());
    }


    /** 커밋된 설치 입력을 트랜잭션 밖에서 실행하고 현재 시도에 적용한 결과만 돌려준다. */
    public Optional<ResyncOutcome> execute(ConnectorBindingAttempt attempt) {
        return attempts.execute(attempt);
    }


    /** 연결 등록과 확인도 동일한 시작 트랜잭션을 쓰고, 외부 설치를 부르기 전에 커밋한다. */
    public <T> T start(Supplier<T> preparation) {
        return attempts.start(preparation);
    }


    /** 연결과 설치 시작에서 소유자 잠금과 접근 철회 검사를 공유한다. */
    public AppUser lockOwner(Long ownerId) {
        return attempts.lockOwner(ownerId);
    }


    /** 결과가 버려졌거나 그 사이 떼어진 바인딩도 지금 상태로 응답한다. */
    private AgentConnectionView currentView(Long ownerId, Long agentId, String connectorId, ConnectorManifest manifest) {
        return attempts.start(() -> {
            lockUser(ownerId);
            Agent agent = agents.findByIdForUpdate(agentId).filter(candidate -> !candidate.isDeleted())
                .orElseThrow(ConnectorBindingService::agentNotFound);
            if (!Objects.equals(agent.ownerUserId(), ownerId)) {
                throw new ApiException(ErrorCode.AGENT_BUSY, "the agent owner changed while installing");
            }
            ConnectorConnection connection = connections.findByUserIdAndConnectorId(ownerId, connectorId)
                .orElseThrow(ConnectorBindingService::notBound);
            ConnectorBinding binding = bindings.findByAgentIdAndConnectionId(agentId, connection.id()).orElse(null);
            return view(connection, binding, manifest);
        });
    }


    /**
     * 요청자가 주인인 에이전트를 읽는다. {@code AgentLifecycleService} 의 관리 규칙과 같다.
     *
     * <p>읽을 수 없는 에이전트는 없는 에이전트와 같게 {@code AGENT_NOT_FOUND} 다. {@code ADMIN} 은 남의 비공개 에이전트도
     * 번호로 찾는다. 읽을 수 있어도 주인이 아니면 {@code FORBIDDEN} 이고 관리자도 같다. 남이 주인의 계정을 쓰게 되기 때문이다.
     */
    private Agent requireOwned(CurrentUser user, String code, boolean forUpdate) {
        // 잠금은 기다린다. 공개 범위 변경이나 주인 변경이 잠금을 쥐고 있으면 그 커밋을 보고 판정한다. 엔티티를 먼저 읽지
        // 않고 id 로 잠그며 처음 읽어야 기다린 뒤의 값을 본다.
        Optional<Agent> found = forUpdate ? agents.findIdByCode(code).flatMap(agents::findByIdForUpdate) : agents.findByCode(code);
        Agent agent = found.filter(candidate -> !candidate.isDeleted())
            .filter(candidate -> user.isAdmin() || candidate.isReadableBy(user.id()))
            .orElseThrow(ConnectorBindingService::agentNotFound);
        if (!Objects.equals(agent.ownerUserId(), user.id())) {
            throw new ApiException(ErrorCode.FORBIDDEN, "only the owner can manage connections of this agent");
        }
        return agent;
    }


    private void lockUser(Long userId) {
        attempts.lockOwner(userId);
    }


    private static String blockedReason(Agent agent) {
        return agent.visibility() == AgentVisibility.PRIVATE ? null : AgentConnectionsView.AGENT_NOT_PRIVATE;
    }


    /** 카탈로그에서 빠진 커넥터는 이름 대신 번호를 쓰고 도구와 스킬을 모른다. */
    private static AgentConnectionView view(
        ConnectorConnection connection, ConnectorBinding binding, ConnectorManifest manifest) {
        return new AgentConnectionView(
            connection.connectorId(),
            manifest == null ? connection.connectorId() : manifest.title(),
            connection.status(),
            binding != null,
            binding == null ? null : binding.status(),
            binding != null && binding.restartRequired(),
            manifest == null ? 0 : manifest.tools().size(),
            manifest == null ? List.of() : manifest.skills());
    }


    private static ApiException agentNotFound() {
        return new ApiException(ErrorCode.AGENT_NOT_FOUND, "no such agent");
    }


    private static ApiException notBound() {
        return new ApiException(ErrorCode.VALIDATION_FAILED, "this agent has no such connection");
    }


    private Instant now() {
        return Instant.now(clock);
    }
}
