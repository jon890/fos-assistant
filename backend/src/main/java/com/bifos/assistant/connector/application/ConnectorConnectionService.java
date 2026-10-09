package com.bifos.assistant.connector.application;

import com.bifos.assistant.browser.application.BrowserGatewayTokens;
import com.bifos.assistant.connector.application.model.AdminConnectionSnapshot;
import com.bifos.assistant.connector.application.model.BoundAgentSummary;
import com.bifos.assistant.connector.application.model.ConnectionSnapshot;
import com.bifos.assistant.connector.application.model.ConnectorFieldSummary;
import com.bifos.assistant.connector.application.model.ConnectorOperationFailure;
import com.bifos.assistant.connector.application.model.ConnectorOption;
import com.bifos.assistant.connector.application.model.ConnectorSummary;
import com.bifos.assistant.connector.application.model.ResyncOutcome;
import com.bifos.assistant.connector.domain.ConnectorBinding;
import com.bifos.assistant.connector.domain.ConnectorConnection;
import com.bifos.assistant.connector.domain.type.ConnectionStatus;
import com.bifos.assistant.connector.infra.ConnectorBindingRepository;
import com.bifos.assistant.connector.infra.ConnectorConnectionRepository;
import com.bifos.assistant.hermes.HermesConnectorClient;
import com.bifos.assistant.hermes.dto.CallResult;
import com.bifos.assistant.hermes.dto.ConnectorAppearance;
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
 * 커넥터 카탈로그와 사용자별 연결의 등록, 확인, 해제를 맡는다(ADR-043, ADR-083).
 *
 * <p>연결은 사용자와 커넥터마다 하나이고 에이전트를 만들지 않는다. 칸 값의 원본은 대시보드 plugin 이 연결마다 두는 보관
 * 파일이고, 에이전트에는 {@link ConnectorBindingService} 가 바인딩으로 붙인다. 값을 바꾸거나 해제하면 붙은 바인딩마다 그
 * profile 을 다시 쓰거나 뗀다.
 *
 * <p>서비스의 이름, env 이름, MCP 서버 이름을 갖지 않는다. 모두 대시보드가 내는 manifest 에서 꺼낸다. 카탈로그는 요청마다
 * 다시 읽는다. 운영자가 목록을 바꾸면 바로 반영되어야 하고 호출이 드물기 때문이다. 순서와 실패 처리는
 * {@code docs/features/connector.md} 의 「설치와 실패 처리」 가 갖는다.
 */
@Slf4j
@Service
public class ConnectorConnectionService {
    private static final String STEP_VAULT = "vault";
    private static final String STEP_VAULT_IMPORT = "vault-import";
    private static final String STEP_DETACH = "detach";
    private static final String STEP_TOOL_CALL = "tool-call";

    private final ConnectorConnectionRepository connections;
    private final ConnectorBindingRepository bindings;
    private final AppUserRepository users;
    private final ConnectorBindingService bindingService;
    private final HermesConnectorClient connector;
    private final TransactionTemplate transactions;
    private final ConnectorCallLimiter limiter;
    private final ConnectorActionService approvals;
    private final BrowserGatewayTokens tokens;
    private final Clock clock;

    /**
     * 연결 확인의 앞 트랜잭션이 본 것이다. 확인 도구를 부를지 정한다.
     *
     * @param vault 확인 도구가 값을 읽을 보관 파일. 값이 보관 파일에 없거나 해제된 연결이면 null
     */
    private record Prepared(String vault) {}

    /** 커밋한 뒤에 알릴 실패와 함께 돌려주는 연결 확인의 결과다. */
    private record Checked(ConnectionSnapshot snapshot, ApiException failure) {}

    // 생성자를 직접 쓴다. TransactionTemplate 은 transaction manager 로 여기서 만들고,
    // 검사가 시각을 고정할 수 있게 Clock 을 받는 생성자를 따로 둔다.
    @Autowired
    public ConnectorConnectionService(
            ConnectorConnectionRepository connections,
            ConnectorBindingRepository bindings,
            AppUserRepository users,
            ConnectorBindingService bindingService,
            HermesConnectorClient connector,
            PlatformTransactionManager transactionManager,
            ConnectorCallLimiter limiter,
            ConnectorActionService approvals,
            BrowserGatewayTokens tokens) {
        this(
                connections,
                bindings,
                users,
                bindingService,
                connector,
                transactionManager,
                limiter,
                approvals,
                tokens,
                Clock.systemUTC());
    }

    public ConnectorConnectionService(
            ConnectorConnectionRepository connections,
            ConnectorBindingRepository bindings,
            AppUserRepository users,
            ConnectorBindingService bindingService,
            HermesConnectorClient connector,
            PlatformTransactionManager transactionManager,
            ConnectorCallLimiter limiter,
            ConnectorActionService approvals,
            BrowserGatewayTokens tokens,
            Clock clock) {
        this.connections = connections;
        this.bindings = bindings;
        this.users = users;
        this.bindingService = bindingService;
        this.connector = connector;
        this.transactions = new TransactionTemplate(transactionManager);
        this.limiter = limiter;
        this.approvals = approvals;
        this.tokens = tokens;
        this.clock = clock;
    }

    /**
     * 카탈로그의 커넥터와 그것에 대한 내 상태다. env 이름과 도구 이름은 여기서 빠진다.
     *
     * <p>카탈로그에서 빠졌지만 아직 해제하지 않은 내 연결은 쓸 수 없는 항목으로 뒤에 덧붙인다. 화면이 해제하러
     * 들어갈 길을 남기기 위해서다. 선언을 알 수 없으므로 이름은 커넥터 번호이고 칸은 비어 있다.
     */
    @Transactional(readOnly = true)
    public List<ConnectorSummary> catalog(CurrentUser user) {
        List<ConnectorManifest> manifests = ConnectorManifests.read(connector);
        Map<String, ConnectorConnection> mine = connections.findByUserId(user.id()).stream()
                .collect(Collectors.toMap(ConnectorConnection::connectorId, connection -> connection));
        List<ConnectorSummary> result = new ArrayList<>();
        for (ConnectorManifest manifest : manifests) {
            ConnectorConnection connection = mine.remove(manifest.id());
            result.add(new ConnectorSummary(
                    manifest.id(),
                    manifest.title(),
                    manifest.description(),
                    manifest.appearance(),
                    manifest.fields().stream().map(ConnectorFieldSummary::from).toList(),
                    ConnectorToolPolicies.summaries(manifest),
                    connection == null ? ConnectionStatus.DISCONNECTED : connection.status(),
                    true,
                    connection == null ? List.of() : boundAgents(connection),
                    manifest.ownerBrowserLoginUrl()));
        }
        mine.values().stream()
                .filter(connection -> connection.status() != ConnectionStatus.DISCONNECTED)
                .map(connection -> new ConnectorSummary(
                        connection.connectorId(),
                        connection.connectorId(),
                        "",
                        ConnectorAppearance.NONE,
                        List.of(),
                        List.of(),
                        connection.status(),
                        false,
                        boundAgents(connection)))
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
                connectorId, ConnectionStatus.DISCONNECTED, Map.of(), Map.of(), null, List.of(), 0);
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
            JsonNode result =
                    call(user.id(), manifest, options.tool(), ConnectorValues.validated(manifest, values, false));
            return ConnectorOption.listFrom(result, options).orElseThrow(ConnectorErrors::unavailable);
        });
    }

    /**
     * 값을 등록하거나 교체한다. 입력 칸이 없는 커넥터는 빈 값을 받는다.
     *
     * <p>확인 도구는 DB 트랜잭션 밖에서 먼저 부른다. 최대 10초가 걸려 그동안 DB 연결을 쥐지 않기 위해서다. 통과한
     * 뒤에만 트랜잭션을 열어 사용자 행을 잠그고 저장한다. 그 사이에 같은 사용자의 다른 요청이 끼어도 잠금 안에서
     * 행을 다시 읽으므로 상태 전이는 순서대로 적용된다.
     *
     * <p>보관 파일을 쓰지 못하면 {@code PENDING} 을 커밋한 뒤에 실패를 알린다. 보관 파일을 쓴 뒤에는 연결이 붙은
     * 바인딩마다 설치를 다시 보낸다. 바인딩 하나가 실패해도 연결은 저장하고 그 바인딩만 {@code PENDING} 이다. 이 메서드에
     * {@code @Transactional} 을 붙이지 않는다. 붙이면 확인 호출이 트랜잭션 안으로 들어간다.
     */
    public ConnectionSnapshot register(CurrentUser user, String connectorId, Map<String, String> values) {
        return limiter.call(user.id(), () -> {
            ConnectorManifest manifest = requireManifest(connectorId);
            Map<String, String> accepted = ConnectorValues.validated(manifest, values, true);
            call(user.id(), manifest, manifest.verifyTool(), accepted);
            ConnectionSnapshot stored = transactions.execute(status -> apply(user, manifest, accepted));
            if (stored == null) {
                throw new ConnectorOperationFailure();
            }
            return stored;
        });
    }

    /**
     * 잠금 안에서 보관 파일과 붙은 바인딩을 반영하고 저장한다. 트랜잭션 안에서만 부른다.
     *
     * @return 저장한 상태. 보관 파일을 쓰지 못해 {@code PENDING} 만 남겼으면 null. 예외로 알리면 그 커밋이 되돌려진다
     */
    private ConnectionSnapshot apply(CurrentUser user, ConnectorManifest manifest, Map<String, String> accepted) {
        AppUser owner = lock(user.id());
        Instant now = now();
        ConnectorConnection connection = connections
                .findByUserIdAndConnectorId(owner.id(), manifest.id())
                .orElseGet(() -> connections.save(ConnectorConnection.pending(owner.id(), manifest.id(), now)));
        // 다른 계정의 값으로 바꿀 수 있다. 앞선 값에 한 승인 요청과 상시 허락을 남기지 않는다(ADR-050).
        approvals.rejectPendingFor(connection, now);
        try {
            connector.putVault(connection.vault(), manifest.id(), accepted);
        } catch (RuntimeException ex) {
            warn(STEP_VAULT, manifest.id(), ex);
            connection.pending(now);
            connections.save(connection);
            return null;
        }
        connection.connected(ConnectorValues.stored(manifest, accepted), now);
        List<ConnectorBinding> bound = bindings.findByConnectionId(connection.id());
        for (ConnectorBinding binding : bound) {
            bindingService.reinstall(binding, manifest, accepted);
        }
        bindings.saveAll(bound);
        return ConnectionSnapshot.from(connections.save(connection), bound);
    }

    /**
     * 연결을 해제한다. 붙은 바인딩을 모두 뗀 뒤 보관 파일을 지운다.
     *
     * <p>바인딩마다 떼기가 성공하면 그 행을 바로 지운다. 중간에 실패하면 연결을 {@code PENDING} 으로 두고 연결 실패로
     * 끝낸다. 남은 바인딩의 profile 에는 값이 남아 있고 보관 파일도 그대로다. 다시 해제하면 남은 바인딩부터 이어서 뗀다.
     * 카탈로그에서 빠진 커넥터는 env 이름을 알 수 없어 옛 바인딩의 설치만 끄고 env 는 대시보드가 소유 기록으로 지운다.
     */
    @Transactional(noRollbackFor = ConnectorOperationFailure.class)
    public ConnectionSnapshot disconnect(CurrentUser user, String connectorId) {
        Optional<ConnectorManifest> manifest = findManifest(connectorId);
        lock(user.id());
        ConnectorConnection connection = requireConnection(user.id(), connectorId, manifest.isPresent());
        Instant now = now();
        approvals.rejectPendingFor(connection, now);
        String step = STEP_DETACH;
        try {
            for (ConnectorBinding binding : bindings.findByConnectionId(connection.id())) {
                bindingService.detach(binding, manifest);
            }
            step = STEP_VAULT;
            connector.deleteVault(connection.vault());
        } catch (RuntimeException ex) {
            warn(step, connectorId, ex);
            connection.pending(now);
            connections.save(connection);
            throw new ConnectorOperationFailure();
        }
        connection.disconnected(now);
        return ConnectionSnapshot.from(connections.save(connection), List.of());
    }

    /**
     * 보관 파일의 값으로 확인 도구를 부르고, 붙은 바인딩마다 설치와 반영을 맞춘다.
     *
     * <p>트랜잭션을 셋으로 나눈다. 먼저 잠금 안에서 옛 연결의 값을 그 커넥터 에이전트의 profile 에서 보관 파일로 옮긴다.
     * 다음으로 트랜잭션 밖에서 확인 도구를 부른다. 최대 10초가 걸려 그동안 DB 연결과 사용자 잠금을 쥐지 않는다. 끝으로 다시
     * 잠그고 결과를 적은 뒤 바인딩마다 설치를 맞춘다. 이 메서드에 {@code @Transactional} 을 붙이지 않는다.
     *
     * <p>확인 도구가 실패하면 연결을 {@code PENDING} 으로 커밋하고 공통 어휘의 오류로 끝낸다. 옛 연결의 값을 옮기지
     * 못했으면 확인 도구를 부르지 않고 연결의 상태를 그대로 둔다. 그 연결은 다른 에이전트에 붙지 못한다. 보관 파일이 없고 값을
     * 옮겨 올 옛 바인딩도 없으면 확인할 값이 없다. 카탈로그에 있는 커넥터면 연결을 {@code PENDING} 으로 커밋하고
     * {@code CONNECTOR_NOT_CONNECTED} 로 끝내 값을 다시 등록하게 한다. 바인딩의 외부 호출이 실패하면 그 바인딩만
     * {@code PENDING} 으로 커밋하고 연결 실패로 끝낸다.
     */
    public ConnectionSnapshot check(CurrentUser user, String connectorId) {
        return limiter.call(user.id(), () -> {
            Optional<ConnectorManifest> manifest = findManifest(connectorId);
            Prepared prepared = transactions.execute(status -> prepare(user, connectorId, manifest.isPresent()));
            if (prepared == null) {
                throw new ConnectorOperationFailure();
            }
            ApiException verifyFailure = null;
            boolean verified = false;
            if (prepared.vault() != null && manifest.isPresent()) {
                verifyFailure = verify(user.id(), manifest.get(), prepared.vault());
                verified = verifyFailure == null;
            }
            ApiException failure = verifyFailure;
            boolean ready = verified;
            Checked checked = transactions.execute(status -> recordCheck(user, connectorId, manifest, ready, failure));
            if (checked == null) {
                throw new ConnectorOperationFailure();
            }
            if (checked.failure() != null) {
                throw checked.failure();
            }
            return checked.snapshot();
        });
    }

    /** 연결 확인의 앞 트랜잭션이다. 옛 연결이면 그 커넥터 에이전트의 profile 에 있던 값을 보관 파일로 옮긴다. */
    private Prepared prepare(CurrentUser user, String connectorId, boolean known) {
        lock(user.id());
        ConnectorConnection connection = requireConnection(user.id(), connectorId, known);
        if (connection.status() == ConnectionStatus.DISCONNECTED) {
            return new Prepared(null);
        }
        if (!connection.vaultStored()) {
            Optional<ConnectorBinding> legacy = bindings.findByConnectionId(connection.id()).stream()
                    .filter(binding -> binding.agent().connectorManaged())
                    .findFirst();
            if (legacy.isPresent()) {
                try {
                    connector.importVault(
                            connection.vault(),
                            connectorId,
                            legacy.get().agent().hermesProfile());
                    connection.markVaultStored();
                    connections.save(connection);
                } catch (RuntimeException ex) {
                    warn(STEP_VAULT_IMPORT, connectorId, ex);
                }
            }
        }
        return new Prepared(connection.vaultStored() ? connection.vault() : null);
    }

    /** 보관 파일의 값으로 확인 도구를 부른다. 실패는 공통 어휘의 오류로 돌려준다. */
    private ApiException verify(long userId, ConnectorManifest manifest, String vault) {
        final CallResult result;
        try {
            result = connector.callWithVault(
                    manifest.id(), manifest.verifyTool(), vault, ownerBrowser(userId, manifest));
        } catch (RuntimeException ex) {
            warn(STEP_TOOL_CALL, manifest.id(), ex);
            return ConnectorErrors.unavailable();
        }
        return result.ok() ? null : ConnectorErrors.of(result.error());
    }

    /**
     * 연결 확인의 뒤 트랜잭션이다. 다시 잠그고 확인 결과를 적은 뒤 바인딩마다 설치를 맞춘다.
     *
     * <p>그 사이 해제됐으면 아무것도 바꾸지 않는다. 확인 도구가 실패했으면 바인딩은 건드리지 않는다. 보관 파일이 없고 옛
     * 바인딩도 없으면 {@code PENDING} 으로 두고 {@code CONNECTOR_NOT_CONNECTED} 를 돌려준다. 카탈로그에서 빠진 커넥터는
     * 확인할 수도 다시 등록할 수도 없어 보관 파일이 없어도 오류 없이 {@code PENDING} 이다.
     */
    private Checked recordCheck(
            CurrentUser user,
            String connectorId,
            Optional<ConnectorManifest> manifest,
            boolean verified,
            ApiException verifyFailure) {
        lock(user.id());
        ConnectorConnection connection = requireConnection(user.id(), connectorId, manifest.isPresent());
        if (connection.status() == ConnectionStatus.DISCONNECTED) {
            return new Checked(snapshot(connection), null);
        }
        Instant now = now();
        if (verifyFailure != null) {
            connection.pending(now);
            return new Checked(snapshot(connections.save(connection)), verifyFailure);
        }
        List<ConnectorBinding> bound = bindings.findByConnectionId(connection.id());
        if (manifest.isPresent()
                && !connection.vaultStored()
                && bound.stream().noneMatch(binding -> binding.agent().connectorManaged())) {
            // 값이 보관 파일에도 옛 에이전트의 profile 에도 없다. 확인할 값이 없으니 다시 등록해야 쓸 수 있다.
            // 카탈로그에서 빠진 커넥터는 다시 등록할 수도 없어 아래의 PENDING 으로 오류 없이 끝낸다.
            connection.pending(now);
            return new Checked(
                    snapshot(connections.save(connection)),
                    new ApiException(
                            ErrorCode.CONNECTOR_NOT_CONNECTED, "register the values of this connection again"));
        }
        if (verified) {
            connection.ready(now);
        } else if (manifest.isEmpty()) {
            connection.pending(now);
        }
        boolean failed = false;
        for (ConnectorBinding binding : bound) {
            failed |= bindingService.resync(binding, manifest, false) == ResyncOutcome.CALL_FAILED;
        }
        bindings.saveAll(bound);
        ConnectionSnapshot stored = ConnectionSnapshot.from(connections.save(connection), bound);
        return new Checked(stored, failed ? new ConnectorOperationFailure() : null);
    }

    /** 관리자가 보는 같은 그룹 사용자의 바인딩들이다. 반영 완료는 바인딩마다 누른다. */
    @Transactional(readOnly = true)
    public List<AdminConnectionSnapshot> listForAdmin(CurrentUser admin) {
        requireAdmin(admin);
        Map<Long, String> names = users.findAll().stream()
                .filter(user -> user.groupId().equals(admin.groupId()))
                .collect(Collectors.toMap(AppUser::id, AppUser::displayName));
        return bindings.findByConnectionUserIdIn(List.copyOf(names.keySet())).stream()
                .map(binding -> AdminConnectionSnapshot.from(
                        binding, names.get(binding.connection().userId())))
                .toList();
    }

    /** 도구를 부르고 성공 결과를 돌려준다. 실패는 공통 어휘에 맞는 오류 코드로 끝낸다. */
    private JsonNode call(long userId, ConnectorManifest manifest, String tool, Map<String, String> values) {
        final CallResult result;
        try {
            result = connector.call(manifest.id(), tool, values, ownerBrowser(userId, manifest));
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
     * 확인 도구와 선택지 호출에 실을 브라우저 중계 주소다. 사용자 브라우저를 쓰지 않는 커넥터는 null 이라 본문에 키가 없다.
     *
     * <p>바인딩이 없는 호출이라 요청자의 호출 표식을 싣는다. 중계가 꺼졌으면 빈 값이다(ADR-20261008 / browser-gateway-token).
     */
    private String ownerBrowser(long userId, ConnectorManifest manifest) {
        return manifest.ownerBrowser() ? tokens.callAddress(userId).orElse("") : null;
    }

    private ConnectionSnapshot snapshot(ConnectorConnection connection) {
        return ConnectionSnapshot.from(connection, bindings.findByConnectionId(connection.id()));
    }

    private List<BoundAgentSummary> boundAgents(ConnectorConnection connection) {
        return bindings.findByConnectionId(connection.id()).stream()
                .map(BoundAgentSummary::from)
                .toList();
    }

    private Optional<ConnectorManifest> findManifest(String connectorId) {
        return ConnectorManifests.find(connector, connectorId);
    }

    private ConnectorManifest requireManifest(String connectorId) {
        return findManifest(connectorId).orElseThrow(ConnectorErrors::notFound);
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
