package com.bifos.assistant.connector.application;

import com.bifos.assistant.agent.application.AgentLifecycleService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.connector.application.model.AdminConnectionSnapshot;
import com.bifos.assistant.connector.application.model.ConnectionSnapshot;
import com.bifos.assistant.connector.application.model.ConnectorFieldSummary;
import com.bifos.assistant.connector.application.model.ConnectorOperationFailure;
import com.bifos.assistant.connector.application.model.ConnectorOption;
import com.bifos.assistant.connector.application.model.ConnectorSummary;
import com.bifos.assistant.connector.domain.ConnectorConnection;
import com.bifos.assistant.connector.domain.type.ConnectionStatus;
import com.bifos.assistant.connector.infra.ConnectorConnectionRepository;
import com.bifos.assistant.hermes.HermesConnectorClient;
import com.bifos.assistant.hermes.HermesConnectorClient.ConnectorState;
import com.bifos.assistant.hermes.HermesConnectorClient.InstallResult;
import com.bifos.assistant.hermes.HermesConnectorClient.ProbeResult;
import com.bifos.assistant.hermes.HermesToolsetClient;
import com.bifos.assistant.hermes.dto.CallResult;
import com.bifos.assistant.hermes.dto.ConnectorField;
import com.bifos.assistant.hermes.dto.ConnectorFieldOptions;
import com.bifos.assistant.hermes.dto.ConnectorManifest;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * 커넥터 카탈로그와 사용자별 연결의 등록, 확인, 해제를 맡는다(ADR-039, ADR-043).
 *
 * <p>서비스의 이름, env 이름, MCP 서버 이름을 갖지 않는다. 모두 대시보드가 내는 manifest 에서 꺼낸다. 카탈로그는
 * 요청마다 다시 읽는다. 운영자가 목록을 바꾸면 바로 반영되어야 하고 호출이 드물기 때문이다. 순서와 실패 처리는
 * {@code docs/connectors.md} 의 「설치와 실패 처리」 가 갖는다.
 */
@Slf4j
@Service
public class ConnectorConnectionService {
    private static final String STEP_ENV = "env";
    private static final String STEP_INSTALL = "install";
    private static final String STEP_INSTALL_STATE = "install-state";
    private static final String STEP_PROBE = "probe";
    private static final String STEP_TOOL_CALL = "tool-call";

    private final ConnectorConnectionRepository connections;
    private final AppUserRepository users;
    private final AgentLifecycleService lifecycle;
    private final HermesConnectorClient connector;
    private final HermesToolsetClient toolsets;
    private final TransactionTemplate transactions;
    private final ConnectorCallLimiter limiter;
    private final Clock clock;

    // 생성자를 직접 쓴다. TransactionTemplate 은 transaction manager 로 여기서 만들고,
    // 검사가 시각을 고정할 수 있게 Clock 을 받는 생성자를 따로 둔다.
    @Autowired
    public ConnectorConnectionService(
            ConnectorConnectionRepository connections,
            AppUserRepository users,
            AgentLifecycleService lifecycle,
            HermesConnectorClient connector,
            HermesToolsetClient toolsets,
            PlatformTransactionManager transactionManager,
            ConnectorCallLimiter limiter) {
        this(connections, users, lifecycle, connector, toolsets, transactionManager, limiter, Clock.systemUTC());
    }

    public ConnectorConnectionService(
            ConnectorConnectionRepository connections,
            AppUserRepository users,
            AgentLifecycleService lifecycle,
            HermesConnectorClient connector,
            HermesToolsetClient toolsets,
            PlatformTransactionManager transactionManager,
            ConnectorCallLimiter limiter,
            Clock clock) {
        this.connections = connections;
        this.users = users;
        this.lifecycle = lifecycle;
        this.connector = connector;
        this.toolsets = toolsets;
        this.transactions = new TransactionTemplate(transactionManager);
        this.limiter = limiter;
        this.clock = clock;
    }

    /**
     * 카탈로그의 커넥터와 그것에 대한 내 상태다. env 이름과 도구 이름은 여기서 빠진다.
     *
     * <p>카탈로그에서 빠졌지만 아직 해제하지 않은 내 연결은 쓸 수 없는 항목으로 뒤에 덧붙인다. 화면이 해제하러
     * 들어갈 길을 남기기 위해서다. 선언을 알 수 없으므로 이름은 전용 에이전트의 이름이고 칸은 비어 있다.
     */
    public List<ConnectorSummary> catalog(CurrentUser user) {
        List<ConnectorManifest> manifests = readCatalog();
        Map<String, ConnectorConnection> mine = connections.findByUserId(user.id()).stream()
                .collect(Collectors.toMap(ConnectorConnection::connectorId, connection -> connection));
        List<ConnectorSummary> result = new ArrayList<>();
        for (ConnectorManifest manifest : manifests) {
            ConnectorConnection connection = mine.remove(manifest.id());
            result.add(new ConnectorSummary(
                    manifest.id(),
                    manifest.title(),
                    manifest.description(),
                    manifest.fields().stream().map(ConnectorFieldSummary::from).toList(),
                    ConnectorToolPolicies.summaries(manifest),
                    connection == null ? ConnectionStatus.DISCONNECTED : connection.status(),
                    true));
        }
        mine.values().stream()
                .filter(connection -> connection.status() != ConnectionStatus.DISCONNECTED)
                .map(connection -> new ConnectorSummary(
                        connection.connectorId(),
                        connection.agent().name(),
                        "",
                        List.of(),
                        List.of(),
                        connection.status(),
                        false))
                .forEach(result::add);
        return List.copyOf(result);
    }

    /** 이미 연결 행이 있으면 카탈로그에서 빠진 커넥터도 읽는다. */
    @Transactional(readOnly = true)
    public ConnectionSnapshot read(CurrentUser user, String connectorId) {
        Optional<ConnectorConnection> connection = connections.findByUserIdAndConnectorId(user.id(), connectorId);
        if (connection.isPresent()) {
            return ConnectionSnapshot.from(connection.get());
        }
        requireManifest(connectorId);
        return new ConnectionSnapshot(
                connectorId, ConnectionStatus.DISCONNECTED, Map.of(), Map.of(), false, null, null, 0);
    }

    /** 작성 중인 값으로 선택지 도구를 부른다. 아무것도 저장하지 않으므로 사용자 행을 잠그지 않는다. */
    public List<ConnectorOption> options(
            CurrentUser user, String connectorId, String fieldKey, Map<String, String> values) {
        return limiter.call(user.id(), () -> {
            ConnectorManifest manifest = requireManifest(connectorId);
            ConnectorFieldOptions options = manifest.fields().stream()
                    .filter(field -> field.key().equals(fieldKey) && field.options() != null)
                    .map(ConnectorField::options)
                    .findFirst()
                    .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED, "this field has no options"));
            // 고르는 중이라 필수 칸이 아직 비어 있을 수 있다. 채운 칸의 형식만 본다.
            JsonNode result = call(manifest, options.tool(), ConnectorValues.validated(manifest, values, false));
            return ConnectorOption.listFrom(result, options).orElseThrow(ConnectorErrors::unavailable);
        });
    }

    /**
     * 값을 등록하거나 교체한다.
     *
     * <p>확인 도구는 DB 트랜잭션 밖에서 먼저 부른다. 최대 10초가 걸려 그동안 DB 연결을 쥐지 않기 위해서다. 통과한
     * 뒤에만 트랜잭션을 열어 사용자 행을 잠그고 저장한다. 그 사이에 같은 사용자의 다른 요청이 끼어도 잠금 안에서
     * 행을 다시 읽으므로 상태 전이는 순서대로 적용된다.
     *
     * <p>외부 반영이 실패하면 비활성화와 {@code PENDING} 을 커밋한 뒤에 실패를 알린다. 이전 값으로 실행할 수 있는
     * 상태로 되돌리지 않는다. 이 메서드에 {@code @Transactional} 을 붙이지 않는다. 붙이면 확인 호출이 트랜잭션
     * 안으로 들어간다.
     */
    public ConnectionSnapshot register(CurrentUser user, String connectorId, Map<String, String> values) {
        return limiter.call(user.id(), () -> {
            ConnectorManifest manifest = requireManifest(connectorId);
            Map<String, String> accepted = ConnectorValues.validated(manifest, values, true);
            call(manifest, manifest.verifyTool(), accepted);
            ConnectionSnapshot stored = transactions.execute(status -> apply(user, manifest, accepted));
            if (stored == null) {
                throw new ConnectorOperationFailure();
            }
            return stored;
        });
    }

    /**
     * 잠금 안에서 env 와 설치를 반영하고 저장한다. 트랜잭션 안에서만 부른다.
     *
     * @return 저장한 상태. 외부 반영이 실패해 {@code PENDING} 만 남겼으면 null. 예외로 알리면 그 커밋이 되돌려진다
     */
    private ConnectionSnapshot apply(CurrentUser user, ConnectorManifest manifest, Map<String, String> accepted) {
        AppUser owner = lock(user.id());
        ConnectorConnection connection = connections
                .findByUserIdAndConnectorId(owner.id(), manifest.id())
                .orElseGet(() -> {
                    Agent agent = lifecycle.createConnectorAgent(user, manifest.title());
                    return connections.save(ConnectorConnection.pending(owner.id(), manifest.id(), agent, now()));
                });
        connection.beginRegister(now());
        connections.save(connection);
        String profile = connection.agent().hermesProfile();
        String step = STEP_ENV;
        try {
            for (ConnectorField field : manifest.fields()) {
                String value = accepted.get(field.key());
                // 비운 선택 칸은 앞선 등록이 남긴 값을 지운다.
                connection.markRestartRequired(
                        value == null
                                ? connector.deleteEnv(profile, field.env())
                                : connector.putEnv(profile, field.env(), value));
            }
            // 도구 목록은 쓰지 않는다. 설치가 API 도구 목록을 커넥터의 MCP 서버 이름과 manifest 가 선언한
            // 내장 toolset 으로 다시 쓴다.
            step = STEP_INSTALL;
            // hook plugin 파일이 바뀌었으면 떠 있는 gateway 가 옛 코드를 쥐고 있을 수 있다. 재시작 대기로 둔다.
            InstallResult installed = connector.putConnector(profile, manifest.id(), true);
            connection.markRestartRequired(installed.restartRequired() || installed.pluginUpdated());
        } catch (RuntimeException ex) {
            warn(step, manifest.id(), ex);
            connection.pending(now());
            connections.save(connection);
            return null;
        }
        // 등록 직후는 선언한 toolset 이 켜졌는지 보기 전이다. 사진은 연결 확인이 그것을 본 뒤에 받는다.
        connection.registered(ConnectorValues.stored(manifest, accepted), false, now());
        return ConnectionSnapshot.from(connections.save(connection));
    }

    /**
     * 연결을 해제한다.
     *
     * <p>카탈로그에서 빠진 커넥터는 env 이름을 알 수 없다. 설치만 끄고 env 는 대시보드가 소유 기록으로 지우게 둔 뒤
     * 재시작 대기로 남긴다.
     */
    @Transactional(noRollbackFor = ConnectorOperationFailure.class)
    public ConnectionSnapshot disconnect(CurrentUser user, String connectorId) {
        Optional<ConnectorManifest> manifest = findManifest(connectorId);
        lock(user.id());
        ConnectorConnection connection = requireConnection(user.id(), connectorId, manifest.isPresent());
        connection.beginDisconnect(now());
        String profile = connection.agent().hermesProfile();
        String step = STEP_ENV;
        try {
            for (ConnectorField field : manifest.map(ConnectorManifest::fields).orElse(List.of())) {
                connection.markRestartRequired(connector.deleteEnv(profile, field.env()));
            }
            step = STEP_INSTALL;
            connection.markRestartRequired(
                    connector.putConnector(profile, connectorId, false).restartRequired());
        } catch (RuntimeException ex) {
            warn(step, connectorId, ex);
            throw new ConnectorOperationFailure();
        }
        connection.disconnected(manifest.isEmpty(), now());
        return ConnectionSnapshot.from(connections.save(connection));
    }

    /**
     * 설치와 probe 를 보고 연결 상태를 맞춘다.
     *
     * <p>재시작 대기인 연결은 설치를 다시 보내지 않는다. 관리자가 재시작한 뒤 반영 완료에서 다시 보낸다.
     */
    @Transactional(noRollbackFor = ConnectorOperationFailure.class)
    public ConnectionSnapshot check(CurrentUser user, String connectorId) {
        return limiter.call(user.id(), () -> {
            lock(user.id());
            ConnectorConnection connection = requireConnection(user.id(), connectorId);
            ConnectorState state = readState(connection);
            if (!connection.desiredEnabled()) {
                if (state.enabled()) {
                    connection.pending(now());
                } else {
                    connection.disconnected(connection.restartRequired(), now());
                }
                return ConnectionSnapshot.from(connections.save(connection));
            }
            if (!connection.restartRequired() && state.enabled() && resyncedUsable(connection)) {
                connection.ready(now());
            } else {
                connection.pending(now());
            }
            return ConnectionSnapshot.from(connections.save(connection));
        });
    }

    /** 관리자가 공유 gateway 를 재시작한 뒤 누르는 반영 완료다. 같은 그룹의 사용자에게만 된다. */
    @Transactional(noRollbackFor = ConnectorOperationFailure.class)
    public ConnectionSnapshot confirmApplied(CurrentUser admin, String connectorId, Long userId) {
        requireAdmin(admin);
        AppUser target = users.findByIdForUpdate(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN, "no such user"));
        if (!target.groupId().equals(admin.groupId())) {
            throw new ApiException(ErrorCode.FORBIDDEN, "no such user");
        }
        ConnectorConnection connection = requireConnection(userId, connectorId);
        ConnectorState state = readState(connection);
        boolean applied =
                connection.desiredEnabled() ? state.enabled() && resyncedUsable(connection) : !state.enabled();
        if (!applied) {
            // 외부 호출은 성공했지만 반영이 끝나지 않았다. 실패 로그를 남기지 않는다.
            connection.pending(now());
            throw new ConnectorOperationFailure();
        }
        if (connection.desiredEnabled()) {
            connection.ready(now());
        } else {
            connection.confirmDisconnected(now());
        }
        return ConnectionSnapshot.from(connections.save(connection));
    }

    /** 설치 상태를 읽는다. 읽지 못하면 {@code PENDING} 을 남기고 연결 실패로 끝낸다. */
    private ConnectorState readState(ConnectorConnection connection) {
        try {
            return connector.readConnector(connection.agent().hermesProfile(), connection.connectorId());
        } catch (RuntimeException ex) {
            warn(STEP_INSTALL_STATE, connection.connectorId(), ex);
            connection.pending(now());
            throw new ConnectorOperationFailure();
        }
    }

    /**
     * 설치를 한 번 다시 보낸 뒤 설치 상태와 MCP probe 와 켜진 내장 도구를 본다.
     *
     * <p>설치가 enabled 인 뒤에만 부른다. 카탈로그에서 빠진 커넥터는 서버 이름을 알 수 없어 쓸 수 없는 것으로
     * 보고 설치도 다시 보내지 않는다. 다시 보내는 설치가 연결용 에이전트의 지침을 지금 plugin 의 스킬 본문에
     * 맞추고 API 도구 목록을 커넥터의 MCP 서버와 manifest 가 선언한 toolset 으로 맞춘다. 이전 판이 설치한 연결이
     * 새 목록을 받는 자리다. 그래서 Control Plane 은 목록을 쓰지 않고, 다시 보낸 뒤에 읽은 설치가 configured 이고
     * 켜진 내장 도구가 선언과 같은지만 본다. 선언 밖의 도구가 켜져 있어도, 선언한 도구가 꺼져 있어도 쓸 수 없다.
     *
     * <p>다시 보내는 설치는 그 profile 의 hook plugin 도 지금 판으로 바꾼다. 파일이 바뀌었으면 떠 있는 gateway 가
     * 옛 코드를 쥐고 있을 수 있으므로 재시작 대기로 두고 쓸 수 없는 것으로 본다. 정책 hook 이 켜져 있는지는 다시
     * 보낸 뒤에 읽은 설치 상태로만 판정한다. 그 앞에 읽은 상태로 거르면 옛 판의 hook 을 가진 연결이 설치를 다시
     * 받지 못한다(ADR-047). probe 가 낸 도구 가운데 manifest 가 선언하지 않은 수는 연결에 적기만 하고 쓸 수 있는지에
     * 넣지 않는다. 그 도구의 호출만 거절된다.
     *
     * <p>manifest 의 사진 받기 선언도 여기서 에이전트에 옮긴다. 선언한 toolset 이 실제로 켜졌을 때만 참으로 둔다.
     * 사진 단추는 있는데 이미지 도구가 없는 상태를 만들지 않기 위해서다. 외부 호출이 실패하면 {@code PENDING} 을
     * 남기고 연결 실패로 끝낸다.
     */
    private boolean resyncedUsable(ConnectorConnection connection) {
        Agent agent = connection.agent();
        String profile = agent.hermesProfile();
        final Optional<ConnectorManifest> manifest;
        try {
            // 카탈로그 조회 실패는 readCatalog 가 이미 로그에 남긴다. 단계 실패로 한 번 더 남기지 않는다.
            manifest = findManifest(connection.connectorId());
        } catch (RuntimeException ex) {
            connection.pending(now());
            throw new ConnectorOperationFailure();
        }
        if (manifest.isEmpty()) {
            return false;
        }
        String step = STEP_INSTALL;
        try {
            // 설치 요청은 같은 값이면 아무것도 바꾸지 않는다. 설치된 연결에는 늘 재시작 필요로 답하므로 그 값은
            // 쓰지 않는다. 실행 정의가 바뀌었으면 요청이 실패한다.
            if (connector.putConnector(profile, connection.connectorId(), true).pluginUpdated()) {
                connection.markRestartRequired(true);
                return false;
            }
            step = STEP_INSTALL_STATE;
            ConnectorState state = connector.readConnector(profile, connection.connectorId());
            if (!state.configured() || !state.policyHook()) {
                return false;
            }
            step = STEP_PROBE;
            ProbeResult probe = connector.probe(profile, manifest.get().mcpServer());
            connection.recordUndeclaredTools(ConnectorToolPolicies.undeclared(manifest.get(), probe.tools()));
            boolean usable = probe.ok()
                    && !probe.tools().isEmpty()
                    && Set.copyOf(manifest.get().toolsets())
                            .equals(Set.copyOf(toolsets.readEnabled(agent.apiBaseUrl(), profile)));
            // 쓸 수 없으면 부른 쪽이 PENDING 으로 두며 사진 받기를 내린다. 외부 호출이 실패해도 같다.
            agent.acceptConnectorAttachments(usable && manifest.get().attachments());
            return usable;
        } catch (RuntimeException ex) {
            warn(step, connection.connectorId(), ex);
            connection.pending(now());
            throw new ConnectorOperationFailure();
        }
    }

    @Transactional(readOnly = true)
    public List<AdminConnectionSnapshot> listForAdmin(CurrentUser admin) {
        requireAdmin(admin);
        Map<Long, String> names = users.findAll().stream()
                .filter(user -> user.groupId().equals(admin.groupId()))
                .collect(Collectors.toMap(AppUser::id, AppUser::displayName));
        return connections.findByUserIdIn(List.copyOf(names.keySet())).stream()
                .map(connection -> AdminConnectionSnapshot.from(connection, names.get(connection.userId())))
                .toList();
    }

    /** 도구를 부르고 성공 결과를 돌려준다. 실패는 공통 어휘에 맞는 오류 코드로 끝낸다. */
    private JsonNode call(ConnectorManifest manifest, String tool, Map<String, String> values) {
        final CallResult result;
        try {
            result = connector.call(manifest.id(), tool, values);
        } catch (RuntimeException ex) {
            warn(STEP_TOOL_CALL, manifest.id(), ex);
            throw ConnectorErrors.unavailable();
        }
        if (!result.ok()) {
            throw ConnectorErrors.of(result.error());
        }
        return result.result();
    }

    /**
     * 카탈로그를 읽는다. manifest 로 열 수 없는 내장 toolset 을 선언한 커넥터와 도구 정책이 하한보다 느슨한
     * 커넥터는 없는 것으로 본다.
     *
     * <p>대시보드가 같은 검사를 먼저 한다. 여기서 한 번 더 보는 것은 셸이나 파일 도구가 대시보드의 결함으로
     * 넘어와도 연결용 에이전트에 켜지지 않게 하고(ADR-044), 느슨한 정책으로 호출을 판정하지 않기 위해서다(ADR-047).
     */
    private List<ConnectorManifest> readCatalog() {
        final List<ConnectorManifest> manifests;
        try {
            manifests = connector.readCatalog();
        } catch (RuntimeException ex) {
            log.warn("connector catalog read failed: {}", ex.getClass().getSimpleName());
            throw ConnectorErrors.unavailable();
        }
        return manifests.stream().filter(ConnectorManifests::accepted).toList();
    }

    private Optional<ConnectorManifest> findManifest(String connectorId) {
        return readCatalog().stream()
                .filter(manifest -> manifest.id().equals(connectorId))
                .findFirst();
    }

    private ConnectorManifest requireManifest(String connectorId) {
        return findManifest(connectorId).orElseThrow(ConnectorErrors::notFound);
    }

    private ConnectorConnection requireConnection(Long userId, String connectorId) {
        Optional<ConnectorConnection> connection = connections.findByUserIdAndConnectorId(userId, connectorId);
        if (connection.isPresent()) {
            return connection.get();
        }
        return requireConnection(userId, connectorId, findManifest(connectorId).isPresent());
    }

    /** 연결 행이 없을 때, 아는 커넥터면 입력 오류이고 모르는 커넥터면 없는 커넥터다. */
    private ConnectorConnection requireConnection(Long userId, String connectorId, boolean known) {
        return connections
                .findByUserIdAndConnectorId(userId, connectorId)
                .orElseThrow(() -> known
                        ? new ApiException(ErrorCode.VALIDATION_FAILED, "no connection for this connector")
                        : ConnectorErrors.notFound());
    }

    private AppUser lock(Long userId) {
        return users.findByIdForUpdate(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED, "sign in first"));
    }

    private static void requireAdmin(CurrentUser admin) {
        if (!admin.isAdmin()) {
            throw new ApiException(ErrorCode.FORBIDDEN, "administrator access is required");
        }
    }

    /** 실패한 단계와 커넥터 번호와 예외 종류만 남긴다. 예외 메시지와 원격 응답에는 칸 값이 섞일 수 있어 적지 않는다. */
    private static void warn(String step, String connectorId, RuntimeException ex) {
        String kind = ex.getClass().getSimpleName();
        log.warn("connector {} failed at {}: {}", connectorId, step, kind);
    }

    private Instant now() {
        return Instant.now(clock);
    }
}
