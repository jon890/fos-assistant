package com.bifos.assistant.connector.application;

import com.bifos.assistant.agent.application.AgentConnectorDetacher;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.connector.application.model.AgentConnectionView;
import com.bifos.assistant.connector.application.model.AgentConnectionsView;
import com.bifos.assistant.connector.application.model.ConnectorOperationFailure;
import com.bifos.assistant.connector.domain.ConnectorBinding;
import com.bifos.assistant.connector.domain.ConnectorConnection;
import com.bifos.assistant.connector.domain.type.BindingStatus;
import com.bifos.assistant.connector.domain.type.ConnectionStatus;
import com.bifos.assistant.connector.infra.ConnectorBindingRepository;
import com.bifos.assistant.connector.infra.ConnectorConnectionRepository;
import com.bifos.assistant.hermes.ConnectorInstallConflict;
import com.bifos.assistant.hermes.ConnectorProfileRejected;
import com.bifos.assistant.hermes.ConnectorSandboxUnavailable;
import com.bifos.assistant.hermes.HermesConnectorClient;
import com.bifos.assistant.hermes.HermesConnectorClient.InstallResult;
import com.bifos.assistant.hermes.HermesSkillClient.HermesSkill;
import com.bifos.assistant.hermes.dto.ConnectorField;
import com.bifos.assistant.hermes.dto.ConnectorManifest;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.skill.infra.SkillPublisher;
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
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 연결을 에이전트에 붙이고 떼고, 붙인 바인딩을 그 profile 의 설치와 맞춘다(ADR-083).
 *
 * <p>붙이고 떼는 사람은 그 에이전트의 주인이고 자기 연결만 붙인다. 관리자도 남의 에이전트에는 붙이거나 떼지 못한다.
 * 재시작 없이 반영될 설치는 반영 예정 시각이 지나면 {@link ConnectorBindingApplier#applyDue()} 가 스스로
 * 확인한다(ADR-20261007 / connector-live-reload). 관리자는 재시작이 필요한 바인딩만 공유 gateway 를 재시작한 뒤 반영 완료를 누른다. 붙이기와 떼기,
 * 반영 완료, 예약 확인은 사용자 행을 먼저, 에이전트 행을 다음에 잠근다. 공개 범위 변경과 관리자 수정도 같은 에이전트 행을
 * 잠그므로 동시에 와도 한쪽이 다른 쪽의 커밋을 보고 판정한다.
 *
 * <p>옛 커넥터 에이전트({@link Agent#connectorManaged()})의 바인딩은 그 에이전트가 지워질 때까지 옛 방식으로 다룬다. 값은
 * 그 profile 의 {@code .env} 에 직접 쓰고 설치는 바인딩 칸 없이 보내며 사진 받기와 내장 도구 선언도 그대로 본다. 외부 호출은
 * 트랜잭션 안에서 한다. 순서와 실패 처리는 {@code docs/backend/connector-install.md} 가 갖는다.
 *
 * <p>{@link #reinstall}, {@link #detach}, {@link #resync} 는 연결 서비스가 자기 트랜잭션 안에서 부르는 단계다. 프록시를
 * 거쳐 부르므로 public 으로 둔다.
 */
@Slf4j
@Service
public class ConnectorBindingService implements AgentConnectorDetacher {
    private static final String STEP_ENV = "env";
    private static final String STEP_INSTALL = "install";
    private static final String STEP_DETACH = "detach";
    private static final String STEP_VAULT_IMPORT = "vault-import";

    private final ConnectorBindingRepository bindings;
    private final ConnectorConnectionRepository connections;
    private final AgentRepository agents;
    private final AppUserRepository users;
    private final HermesConnectorClient connector;
    private final SkillPublisher skills;
    private final ConnectorActionService approvals;
    private final TransactionTemplate transactions;
    private final ConnectorBindingInstalls installs;
    private final Clock clock;

    /**
     * 반영 완료의 트랜잭션이 본 결과다. 확인하지 못한 상태도 커밋한 뒤에 실패를 알리려고 값으로 돌려준다.
     *
     * @param view 반영을 확인한 바인딩의 모습. 확인하지 못했으면 null
     */
    private record Confirmed(AgentConnectionView view) {}

    // TransactionTemplate 은 transaction manager 로 여기서 만들고, 검사가 시각을 고정할 수 있게 Clock 을 받는 생성자를 따로 둔다.
    @Autowired
    public ConnectorBindingService(
            ConnectorBindingRepository bindings,
            ConnectorConnectionRepository connections,
            AgentRepository agents,
            AppUserRepository users,
            HermesConnectorClient connector,
            SkillPublisher skills,
            ConnectorActionService approvals,
            PlatformTransactionManager transactionManager,
            ConnectorBindingInstalls installs) {
        this(
                bindings,
                connections,
                agents,
                users,
                connector,
                skills,
                approvals,
                transactionManager,
                installs,
                Clock.systemUTC());
    }

    public ConnectorBindingService(
            ConnectorBindingRepository bindings,
            ConnectorConnectionRepository connections,
            AgentRepository agents,
            AppUserRepository users,
            HermesConnectorClient connector,
            SkillPublisher skills,
            ConnectorActionService approvals,
            PlatformTransactionManager transactionManager,
            ConnectorBindingInstalls installs,
            Clock clock) {
        this.bindings = bindings;
        this.connections = connections;
        this.agents = agents;
        this.users = users;
        this.connector = connector;
        this.skills = skills;
        this.approvals = approvals;
        this.transactions = new TransactionTemplate(transactionManager);
        this.installs = installs;
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
                .map(connection ->
                        view(connection, bound.get(connection.id()), manifests.get(connection.connectorId())))
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
     * <p>대시보드가 409 나 401 로 거절하면 대시보드는 아무것도 바꾸지 않았고, 이 트랜잭션이 되돌려져 바인딩 행도 남지 않는다.
     * 그 밖의 외부 실패는 바인딩을 {@code PENDING} 으로 남기고 연결 실패로 끝낸다. 대시보드가 반쯤 반영했을 수 있어 다음 연결
     * 확인이 그 바인딩의 설치를 다시 보낸다.
     *
     * <p>커넥터가 {@code single_binding} 을 선언했고 그 연결이 이미 다른 에이전트에 붙어 있으면 설치 전에 거절한다.
     * 대시보드가 409 {@code sandbox_unavailable} 로 거절하면 실행 공간이 없다는 다른 오류로 옮긴다.
     */
    @Transactional(noRollbackFor = ConnectorOperationFailure.class)
    public AgentConnectionView bind(CurrentUser user, String agentCode, String connectorId) {
        lockUser(user.id());
        Agent agent = requireOwned(user, agentCode, true);
        if (agent.connectorManaged()) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "a legacy connection agent cannot take other connections");
        }
        if (agent.visibility() != AgentVisibility.PRIVATE) {
            throw new ApiException(
                    ErrorCode.AGENT_CONNECTIONS_REQUIRE_PRIVATE, "connections can be attached to a private agent only");
        }
        ConnectorConnection connection = connections
                .findByUserIdAndConnectorId(user.id(), connectorId)
                .filter(found -> found.status() == ConnectionStatus.READY && found.vaultStored())
                .orElseThrow(() -> new ApiException(ErrorCode.CONNECTOR_NOT_CONNECTED, "connect this connector first"));
        Optional<ConnectorManifest> manifest = ConnectorManifests.find(connector, connectorId);
        Optional<ConnectorBinding> existing = bindings.findByAgentIdAndConnectionId(agent.id(), connection.id());
        if (existing.isPresent()) {
            return view(connection, existing.get(), manifest.orElse(null));
        }
        ConnectorManifest declared = manifest.orElseThrow(ConnectorErrors::notFound);
        if (declared.singleBinding()
                && !bindings.findByConnectionId(connection.id()).isEmpty()) {
            throw new ApiException(ErrorCode.CONNECTOR_SINGLE_BINDING, "this connection is attached to another agent");
        }
        requireSkillNamesFree(agent, declared);
        Instant now = now();
        ConnectorBinding binding =
                bindings.save(ConnectorBinding.pending(agent, connection, declared.mcpServer(), now));
        binding.beginInstall(now);
        try {
            String ownerBrowser = installs.ownerBrowser(binding, declared);
            InstallResult installed = connector.bindConnector(
                    agent.hermesProfile(), connectorId, connection.vault(), agent.sandboxOwner(), ownerBrowser);
            installs.record(binding, installed, false, now);
        } catch (ConnectorSandboxUnavailable ex) {
            throw new ApiException(
                    ErrorCode.AGENT_SANDBOX_UNAVAILABLE,
                    "the agent profile has no isolated workspace for this connector");
        } catch (ConnectorInstallConflict ex) {
            throw new ApiException(
                    ErrorCode.CONNECTOR_BIND_CONFLICT, "the agent profile conflicts with this connector");
        } catch (ConnectorProfileRejected ex) {
            throw new ApiException(
                    ErrorCode.CONNECTOR_PROFILE_NOT_READY, "the agent profile does not accept connectors yet");
        } catch (RuntimeException ex) {
            warn(STEP_INSTALL, connectorId, ex);
            binding.pending(now);
            bindings.save(binding);
            throw new ConnectorOperationFailure();
        }
        return view(connection, bindings.save(binding), declared);
    }

    /**
     * 내 에이전트에서 연결을 뗀다. 붙어 있지 않으면 아무것도 하지 않는다.
     *
     * <p>그 에이전트가 판정한 승인 줄만 끝낸다. 상시 허락은 사용자와 커넥터에 묶여 다른 에이전트의 바인딩에도 걸리므로 두고,
     * 떼기는 도구 목록에서 이름을 빼므로 재시작을 기다리지 않는다. 옛 커넥터 에이전트의 바인딩은 떼지 않고 그 에이전트를 지운다.
     */
    @Transactional
    public void unbind(CurrentUser user, String agentCode, String connectorId) {
        lockUser(user.id());
        Agent agent = requireOwned(user, agentCode, true);
        if (agent.connectorManaged()) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "delete the legacy connection agent instead of detaching it");
        }
        Optional<ConnectorConnection> connection = connections.findByUserIdAndConnectorId(user.id(), connectorId);
        Optional<ConnectorBinding> binding =
                connection.flatMap(found -> bindings.findByAgentIdAndConnectionId(agent.id(), found.id()));
        if (binding.isEmpty()) {
            return;
        }
        approvals.rejectPendingFor(connection.get(), agent.id(), now());
        try {
            detach(binding.get(), Optional.empty());
        } catch (RuntimeException ex) {
            warn(STEP_DETACH, connectorId, ex);
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
        Confirmed confirmed =
                transactions.execute(status -> confirmLocked(admin, agentId, ownerId, connectorId, shownSince));
        if (confirmed == null || confirmed.view() == null) {
            throw new ConnectorOperationFailure();
        }
        return confirmed.view();
    }

    /**
     * 반영 완료의 트랜잭션이다. 트랜잭션 안에서만 부른다.
     *
     * @return 반영을 확인했으면 그 바인딩의 모습. 확인하지 못했으면 view 가 null 이고, 바인딩의 상태는 커밋된다
     */
    private Confirmed confirmLocked(
            CurrentUser admin, Long agentId, Long ownerId, String connectorId, Instant shownSince) {
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
        Optional<ConnectorManifest> manifest = ConnectorManifests.find(connector, connectorId);
        boolean failed = resync(binding, manifest, true);
        bindings.save(binding);
        if (failed || binding.status() != BindingStatus.READY) {
            return new Confirmed(null);
        }
        return new Confirmed(view(connection, binding, manifest.orElse(null)));
    }

    /**
     * 그 에이전트의 바인딩을 모두 뗀다.
     *
     * <p>옛 커넥터 에이전트의 바인딩이 그 연결의 값을 가진 유일한 곳이면 먼저 보관 파일로 옮긴다. 옮기지 못하면 지우지 않는다.
     * 그 뒤 profile 에서 떼고 행을 지운다. 뒤의 삭제가 실패해 트랜잭션이 되돌려지면 행은 남고 profile 에서는 떼어진 상태다.
     * 다음 연결 확인이 설치가 configured 가 아닌 것을 보고 그 바인딩을 {@code PENDING} 으로 둔다.
     */
    @Override
    @Transactional
    public void detachAll(Agent agent) {
        for (ConnectorBinding binding : bindings.findByAgentId(agent.id())) {
            ConnectorConnection connection = binding.connection();
            String connectorId = connection.connectorId();
            approvals.rejectPendingFor(connection, agent.id(), now());
            Optional<ConnectorManifest> manifest = Optional.empty();
            if (binding.agent().connectorManaged()) {
                if (!connection.vaultStored()) {
                    try {
                        connector.importVault(
                                connection.vault(), connectorId, binding.agent().hermesProfile());
                    } catch (RuntimeException ex) {
                        warn(STEP_VAULT_IMPORT, connectorId, ex);
                        throw new ConnectorOperationFailure();
                    }
                    connection.markVaultStored();
                    connections.save(connection);
                }
                // 옛 설치는 env 이름을 manifest 로 지운다. 카탈로그에서 빠졌으면 대시보드가 소유 기록으로 지운다.
                manifest = ConnectorManifests.find(connector, connectorId);
            }
            try {
                detach(binding, manifest);
            } catch (RuntimeException ex) {
                warn(STEP_DETACH, connectorId, ex);
                throw new ConnectorOperationFailure();
            }
        }
    }

    /**
     * 값을 바꾼 연결의 바인딩에 설치를 다시 보낸다. 연결 등록의 트랜잭션 안에서 부른다.
     *
     * <p>일반 바인딩은 대시보드가 보관 파일에서 값을 다시 복사한다. 옛 바인딩은 받은 값을 지금처럼 그 profile 의 {@code .env}
     * 에 쓴다. 떠 있는 MCP 프로세스는 옛 값을 쥐고 있으므로 재시작 필요를 누적하고 {@code PENDING} 으로 둔다.
     *
     * @return 외부 호출이 실패했는가. 실패해도 예외로 알리지 않고 그 바인딩만 {@code PENDING} 으로 둔다
     */
    public boolean reinstall(ConnectorBinding binding, ConnectorManifest manifest, Map<String, String> accepted) {
        Instant now = now();
        Agent agent = binding.agent();
        String profile = agent.hermesProfile();
        binding.beginInstall(now);
        String step = STEP_INSTALL;
        try {
            if (agent.connectorManaged()) {
                step = STEP_ENV;
                boolean restart = false;
                for (ConnectorField field : manifest.fields()) {
                    String value = accepted.get(field.key());
                    // 비운 선택 칸은 앞선 등록이 남긴 값을 지운다.
                    restart |= value == null
                            ? connector.deleteEnv(profile, field.env())
                            : connector.putEnv(profile, field.env(), value);
                }
                step = STEP_INSTALL;
                InstallResult installed = connector.putConnector(profile, manifest.id(), true, agent.sandboxOwner());
                binding.installed(restart || installed.restartRequired() || installed.pluginUpdated(), now);
            } else {
                String ownerBrowser = installs.ownerBrowser(binding, manifest);
                InstallResult installed = connector.bindConnector(
                        profile, manifest.id(), binding.connection().vault(), agent.sandboxOwner(), ownerBrowser);
                installs.record(binding, installed, false, now);
            }
            if (binding.mcpServer() == null) {
                binding.recordServer(manifest.mcpServer());
            }
            return false;
        } catch (RuntimeException ex) {
            warn(step, manifest.id(), ex);
            binding.pending(now);
            return true;
        }
    }

    /**
     * 바인딩을 profile 에서 떼고 행을 지운다. 외부 호출이 실패하면 예외를 그대로 던지고 행은 남는다.
     *
     * <p>옛 바인딩은 지금처럼 env 를 지우고 설치를 끈 뒤 그 에이전트를 끈다. 카탈로그에서 빠진 커넥터는 env 이름을 모르므로
     * 설치만 끄고 env 는 대시보드가 소유 기록으로 지운다.
     */
    public void detach(ConnectorBinding binding, Optional<ConnectorManifest> manifest) {
        Agent agent = binding.agent();
        String profile = agent.hermesProfile();
        String connectorId = binding.connection().connectorId();
        if (agent.connectorManaged()) {
            for (ConnectorField field : manifest.map(ConnectorManifest::fields).orElse(List.of())) {
                connector.deleteEnv(profile, field.env());
            }
            connector.putConnector(profile, connectorId, false, agent.sandboxOwner());
            // 옛 에이전트는 이 바인딩 하나로 돈다. 끄고 사진 받기를 내린다.
            binding.pending(now());
        } else {
            connector.unbindConnector(profile, connectorId);
        }
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
     * <p>일반 바인딩은 설치가 켜져 있고 configured 이며 정책 hook 이 켜져 있고 바인딩 방식이며 probe 가 도구를 낼 때 쓸 수 있다.
     * 켜진 내장 도구는 보지 않는다. 그 에이전트의 도구는 주인이 정한다. 바인딩 설치는 바뀐 것이 있을 때만 재시작이 필요하다고
     * 답한다.
     *
     * <p>옛 바인딩은 지금 판정 그대로다. 설치가 꺼져 있으면 다시 보내지 않는다. 옛 설치는 설치된 커넥터에 늘 재시작이 필요하다고
     * 답하므로 그 값은 쓰지 않고 hook plugin 파일이 바뀌었을 때만 재시작 대기로 둔다. 켜진 내장 도구가 manifest 의 선언과 같아야
     * 하고, 쓸 수 있을 때만 선언한 사진 받기를 옮긴다.
     *
     * @return 외부 호출이 실패했는가. 실패해도 예외로 알리지 않고 그 바인딩만 {@code PENDING} 으로 둔다
     */
    public boolean resync(ConnectorBinding binding, Optional<ConnectorManifest> manifest, boolean afterRestart) {
        Instant now = now();
        // 재시작 대기로 일찍 돌아가도 서버 이름은 채운다. 관리자 반영 완료의 probe 가 그 이름을 쓴다.
        if (binding.mcpServer() == null && manifest.isPresent()) {
            binding.recordServer(manifest.get().mcpServer());
        }
        // 관리자 반영 완료는 재시작 대기만 넘는다. 반영 예정 시각 전에는 gateway 가 아직 서버를 연결하지 않았을 수 있다.
        // probe 는 gateway 와 별개의 연결이라 그 전에 통과해 READY 가 되면 실제 실행에는 도구가 없다.
        boolean dueLater = binding.applyDueAt() != null && binding.applyDueAt().isAfter(now);
        if ((!afterRestart && binding.restartRequired()) || dueLater || manifest.isEmpty()) {
            binding.pending(now);
            return false;
        }
        return installs.resync(binding, manifest.get(), now);
    }

    /** 스킬 이름이 그 profile 에 이미 있으면 붙이지 않는다. 올린 스킬과 Hermes 스킬을 함께 본다. */
    private void requireSkillNamesFree(Agent agent, ConnectorManifest manifest) {
        if (manifest.skills().isEmpty()) {
            return;
        }
        Set<String> taken = skills.list(agent.hermesProfile()).stream()
                .map(HermesSkill::name)
                .collect(Collectors.toSet());
        if (manifest.skills().stream().anyMatch(taken::contains)) {
            throw new ApiException(
                    ErrorCode.SKILL_NAME_TAKEN, "the agent already has a skill named like a skill of this connector");
        }
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
        Optional<Agent> found =
                forUpdate ? agents.findIdByCode(code).flatMap(agents::findByIdForUpdate) : agents.findByCode(code);
        Agent agent = found.filter(candidate -> !candidate.isDeleted())
                .filter(candidate -> user.isAdmin() || candidate.isReadableBy(user.id()))
                .orElseThrow(ConnectorBindingService::agentNotFound);
        if (!Objects.equals(agent.ownerUserId(), user.id())) {
            throw new ApiException(ErrorCode.FORBIDDEN, "only the owner can manage connections of this agent");
        }
        return agent;
    }

    private void lockUser(Long userId) {
        users.findByIdForUpdate(userId).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED, "sign in first"));
    }

    private static String blockedReason(Agent agent) {
        if (agent.connectorManaged()) {
            return AgentConnectionsView.LEGACY_AGENT;
        }
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

    /** 실패한 단계와 커넥터 번호와 예외 종류만 남긴다. 예외 메시지와 원격 응답에는 칸 값이 섞일 수 있어 적지 않는다. */
    private static void warn(String step, String connectorId, RuntimeException ex) {
        log.warn(
                "connector {} failed at {}: {}",
                connectorId,
                step,
                ex.getClass().getSimpleName());
    }

    private Instant now() {
        return Instant.now(clock);
    }
}
