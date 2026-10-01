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
            PlatformTransactionManager transactionManager) {
        this(connections, users, lifecycle, connector, toolsets, transactionManager, Clock.systemUTC());
    }

    public ConnectorConnectionService(
            ConnectorConnectionRepository connections,
            AppUserRepository users,
            AgentLifecycleService lifecycle,
            HermesConnectorClient connector,
            HermesToolsetClient toolsets,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.connections = connections;
        this.users = users;
        this.lifecycle = lifecycle;
        this.connector = connector;
        this.toolsets = toolsets;
        this.transactions = new TransactionTemplate(transactionManager);
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
                    manifest.fields().stream()
                            .map(ConnectorConnectionService::summary)
                            .toList(),
                    connection == null ? ConnectionStatus.DISCONNECTED : connection.status(),
                    true));
        }
        mine.values().stream()
                .filter(connection -> connection.status() != ConnectionStatus.DISCONNECTED)
                .map(connection -> new ConnectorSummary(
                        connection.connectorId(), connection.agent().name(), "", List.of(), connection.status(), false))
                .forEach(result::add);
        return List.copyOf(result);
    }

    /** 이미 연결 행이 있으면 카탈로그에서 빠진 커넥터도 읽는다. */
    @Transactional(readOnly = true)
    public ConnectionSnapshot read(CurrentUser user, String connectorId) {
        Optional<ConnectorConnection> connection = connections.findByUserIdAndConnectorId(user.id(), connectorId);
        if (connection.isPresent()) {
            return snapshot(connection.get());
        }
        requireManifest(connectorId);
        return new ConnectionSnapshot(
                connectorId, ConnectionStatus.DISCONNECTED, Map.of(), Map.of(), false, null, null);
    }

    /** 작성 중인 값으로 선택지 도구를 부른다. 아무것도 저장하지 않으므로 사용자 행을 잠그지 않는다. */
    public List<ConnectorOption> options(
            CurrentUser user, String connectorId, String fieldKey, Map<String, String> values) {
        ConnectorManifest manifest = requireManifest(connectorId);
        ConnectorFieldOptions options = manifest.fields().stream()
                .filter(field -> field.key().equals(fieldKey) && field.options() != null)
                .map(ConnectorField::options)
                .findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED, "this field has no options"));
        // 고르는 중이라 필수 칸이 아직 비어 있을 수 있다. 채운 칸의 형식만 본다.
        JsonNode result = call(manifest, options.tool(), ConnectorValues.validated(manifest, values, false));
        JsonNode items = result.get(options.items());
        if (items == null || !items.isArray()) {
            throw ConnectorErrors.unavailable();
        }
        List<ConnectorOption> found = new ArrayList<>();
        for (JsonNode item : items) {
            JsonNode value = item.get(options.value());
            JsonNode label = item.get(options.label());
            if (value == null || !value.isString() || label == null || !label.isString()) {
                throw ConnectorErrors.unavailable();
            }
            found.add(new ConnectorOption(value.asString(), label.asString()));
        }
        return List.copyOf(found);
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
        ConnectorManifest manifest = requireManifest(connectorId);
        Map<String, String> accepted = ConnectorValues.validated(manifest, values, true);
        call(manifest, manifest.verifyTool(), accepted);
        ConnectionSnapshot stored = transactions.execute(status -> apply(user, manifest, accepted));
        if (stored == null) {
            throw new ConnectorOperationFailure();
        }
        return stored;
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
            // 도구 목록은 쓰지 않는다. 설치가 API 도구 목록을 커넥터의 MCP 서버 이름만으로 다시 쓴다.
            step = STEP_INSTALL;
            connection.markRestartRequired(connector.putConnector(profile, manifest.id(), true));
        } catch (RuntimeException ex) {
            warn(step, manifest.id(), ex);
            connection.pending(now());
            connections.save(connection);
            return null;
        }
        connection.registered(ConnectorValues.stored(manifest, accepted), false, now());
        return snapshot(connections.save(connection));
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
            connection.markRestartRequired(connector.putConnector(profile, connectorId, false));
        } catch (RuntimeException ex) {
            warn(step, connectorId, ex);
            throw new ConnectorOperationFailure();
        }
        connection.disconnected(manifest.isEmpty(), now());
        return snapshot(connections.save(connection));
    }

    /**
     * 설치와 probe 를 보고 연결 상태를 맞춘다.
     *
     * <p>설치가 켜져 있는데 configured 가 아니면 설치를 한 번 다시 써서 도구 목록을 맞춘다. 재시작 대기인 연결은
     * 다시 쓰지 않는다. 관리자가 재시작한 뒤 반영 완료에서 다시 쓴다.
     */
    @Transactional(noRollbackFor = ConnectorOperationFailure.class)
    public ConnectionSnapshot check(CurrentUser user, String connectorId) {
        lock(user.id());
        ConnectorConnection connection = requireConnection(user.id(), connectorId);
        ConnectorState state = readState(connection);
        if (!connection.desiredEnabled()) {
            if (state.enabled()) {
                connection.pending(now());
            } else {
                connection.disconnected(connection.restartRequired(), now());
            }
            return snapshot(connections.save(connection));
        }
        if (!connection.restartRequired()
                && reinstallIfNotConfigured(connection, state).isPresent()) {
            if (connection.restartRequired()) {
                connection.pending(now());
                return snapshot(connections.save(connection));
            }
            state = readState(connection);
        }
        if (connection.restartRequired() || !state.enabled() || !state.configured()) {
            connection.pending(now());
            return snapshot(connections.save(connection));
        }
        if (probedUsable(connection)) {
            connection.ready(now());
        } else {
            connection.pending(now());
        }
        return snapshot(connections.save(connection));
    }

    /**
     * 관리자가 공유 gateway 를 재시작한 뒤 누르는 반영 완료다. 같은 그룹의 사용자에게만 된다.
     *
     * <p>설치가 켜져 있는데 configured 가 아니면 재시작 대기인 연결이어도 설치를 다시 쓴다. 그 응답이 재시작을
     * 요구하면 실패로 답한다. 관리자가 한 번 더 재시작한 뒤 다시 누른다.
     */
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
        Optional<Boolean> reinstalled = reinstallIfNotConfigured(connection, state);
        if (reinstalled.isPresent()) {
            if (reinstalled.get()) {
                // 다시 쓴 설치는 gateway 를 재시작해야 반영된다. 재시작 대기는 저장된다.
                connection.pending(now());
                throw new ConnectorOperationFailure();
            }
            state = readState(connection);
        }
        boolean applied = connection.desiredEnabled()
                ? state.enabled() && state.configured() && probedUsable(connection)
                : !state.enabled();
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
        return snapshot(connections.save(connection));
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
     * 활성화 후보인 연결의 설치가 켜져 있는데 configured 가 아니면 설치를 다시 쓴다.
     *
     * <p>이전 판이 설치한 연결은 도구 목록에 Control Plane MCP 가 남아 configured 가 아니다. configured 인 설치는
     * 다시 쓰지 않는다. 바뀌는 것 없이 재시작 대기만 서기 때문이다. 카탈로그에서 빠진 커넥터도 다시 쓰지 않는다.
     *
     * @return 다시 썼으면 그 응답의 재시작 필요 여부. 다시 쓰지 않았으면 비어 있다
     */
    private Optional<Boolean> reinstallIfNotConfigured(ConnectorConnection connection, ConnectorState state) {
        if (!connection.desiredEnabled() || !state.enabled() || state.configured()) {
            return Optional.empty();
        }
        try {
            if (findManifest(connection.connectorId()).isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(reinstall(connection));
        } catch (RuntimeException ex) {
            warn(STEP_INSTALL, connection.connectorId(), ex);
            connection.pending(now());
            throw new ConnectorOperationFailure();
        }
    }

    /** 설치를 다시 쓰고 그 응답의 재시작 필요 여부를 연결에 누적한다. 칸 값은 profile 의 env 에 그대로 있다. */
    private boolean reinstall(ConnectorConnection connection) {
        boolean restartRequired =
                connector.putConnector(connection.agent().hermesProfile(), connection.connectorId(), true);
        connection.markRestartRequired(restartRequired);
        return restartRequired;
    }

    /** {@link #usable} 을 부른다. 외부 호출이 실패하면 {@code PENDING} 을 남기고 연결 실패로 끝낸다. */
    private boolean probedUsable(ConnectorConnection connection) {
        try {
            return usable(connection);
        } catch (RuntimeException ex) {
            warn(STEP_PROBE, connection.connectorId(), ex);
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
                .map(connection -> new AdminConnectionSnapshot(
                        connection.connectorId(),
                        connection.userId(),
                        names.get(connection.userId()),
                        connection.status(),
                        connection.agent().code(),
                        connection.restartRequired()))
                .toList();
    }

    /**
     * MCP probe 가 도구를 보이고 켜진 내장 도구가 없는가.
     *
     * <p>설치가 enabled 이고 configured 인 뒤에만 부른다. 카탈로그에서 빠진 커넥터는 서버 이름을 알 수 없어 쓸 수
     * 없는 것으로 본다.
     */
    private boolean usable(ConnectorConnection connection) {
        Optional<ConnectorManifest> manifest = findManifest(connection.connectorId());
        if (manifest.isEmpty()) {
            return false;
        }
        Agent agent = connection.agent();
        ProbeResult probe = connector.probe(agent.hermesProfile(), manifest.get().mcpServer());
        // configured 인 뒤에 보이는 내장 도구는 목록이 아니라 다른 설정에서 온 것이다. 목록을 다시 쓰지 않는다.
        return probe.ok()
                && !probe.tools().isEmpty()
                && toolsets.readEnabled(agent.apiBaseUrl(), agent.hermesProfile()).isEmpty();
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

    private static ConnectorFieldSummary summary(ConnectorField field) {
        ConnectorFieldOptions options = field.options();
        return new ConnectorFieldSummary(
                field.key(),
                field.label(),
                field.description(),
                field.secret(),
                field.required(),
                field.pattern(),
                options != null,
                options != null && options.autoSelectSingle());
    }

    private List<ConnectorManifest> readCatalog() {
        try {
            return connector.readCatalog();
        } catch (RuntimeException ex) {
            log.warn("connector catalog read failed: {}", ex.getClass().getSimpleName());
            throw ConnectorErrors.unavailable();
        }
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

    private static ConnectionSnapshot snapshot(ConnectorConnection value) {
        return new ConnectionSnapshot(
                value.connectorId(),
                value.status(),
                value.fields().secretPrefixes(),
                value.fields().values(),
                value.restartRequired(),
                value.checkedAt(),
                value.agent().code());
    }

    /**
     * 외부 호출이 실패한 단계를 남긴다.
     *
     * <p>단계 이름과 커넥터 번호와 예외 종류만 적는다. 예외 메시지와 원격 응답에는 칸 값이 섞일 수 있어 적지 않는다.
     */
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
