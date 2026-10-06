package com.bifos.assistant.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.connector.application.ConnectorActionService;
import com.bifos.assistant.connector.application.ConnectorBindingService;
import com.bifos.assistant.connector.application.ConnectorCallLimiter;
import com.bifos.assistant.connector.application.ConnectorConnectionService;
import com.bifos.assistant.connector.application.ConnectorProperties;
import com.bifos.assistant.connector.application.model.AdminConnectionSnapshot;
import com.bifos.assistant.connector.application.model.BoundAgentSummary;
import com.bifos.assistant.connector.application.model.ConnectionSnapshot;
import com.bifos.assistant.connector.application.model.ConnectorOperationFailure;
import com.bifos.assistant.connector.application.model.ConnectorOption;
import com.bifos.assistant.connector.application.model.ConnectorSummary;
import com.bifos.assistant.connector.application.model.ConnectorToolSummary;
import com.bifos.assistant.connector.domain.ConnectorBinding;
import com.bifos.assistant.connector.domain.ConnectorConnection;
import com.bifos.assistant.connector.domain.type.BindingStatus;
import com.bifos.assistant.connector.domain.type.ConnectionStatus;
import com.bifos.assistant.connector.domain.type.ToolApproval;
import com.bifos.assistant.connector.domain.type.ToolRisk;
import com.bifos.assistant.connector.infra.ConnectorBindingRepository;
import com.bifos.assistant.connector.infra.ConnectorConnectionRepository;
import com.bifos.assistant.hermes.HermesConnectorClient;
import com.bifos.assistant.hermes.HermesConnectorClient.ConnectorState;
import com.bifos.assistant.hermes.HermesConnectorClient.InstallResult;
import com.bifos.assistant.hermes.HermesConnectorClient.ProbeResult;
import com.bifos.assistant.hermes.HermesToolsetClient;
import com.bifos.assistant.hermes.dto.CallResult;
import com.bifos.assistant.hermes.dto.ConnectorCallError;
import com.bifos.assistant.hermes.dto.ConnectorField;
import com.bifos.assistant.hermes.dto.ConnectorFieldOptions;
import com.bifos.assistant.hermes.dto.ConnectorManifest;
import com.bifos.assistant.hermes.dto.ConnectorTool;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * 연결의 등록, 확인, 해제가 보관 파일과 붙은 바인딩을 다루는 순서와 실패 처리를 본다(ADR-083).
 *
 * <p>대시보드는 대역이다. 붙은 바인딩은 행을 직접 넣어 만든다. 붙이는 경로는 {@code ConnectorBindingServiceTest} 가 본다.
 */
@SpringBootTest
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class ConnectorConnectionServiceTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");
    private static final String DEMO = "demo-notes";
    private static final String PIN = "demo-pin";
    private static final String TOKEN = "demo_ok_0123456789";
    private static final String OTHER_TOKEN = "demo_zz_0123456789";
    private static final String BAD_TOKEN = "demo_bad_0123456789";
    private static final Map<String, String> VALUES = Map.of("token", TOKEN);
    private static final ConnectorManifest DEMO_MANIFEST = new ConnectorManifest(
            DEMO,
            "검사용 메모",
            "검사에서만 쓰는 커넥터입니다.",
            List.of(
                    new ConnectorField("token", "DEMO_TOKEN", "토큰", "", true, true, "^demo_[a-z]+_[0-9]{10}$", null),
                    new ConnectorField(
                            "scope",
                            "DEMO_SCOPE",
                            "범위",
                            "",
                            false,
                            false,
                            null,
                            new ConnectorFieldOptions("list_scopes", "scopes", "id", "name", true))),
            "list_scopes",
            "demo",
            List.of(),
            false,
            1,
            List.of());
    /** 이미지 도구와 사진 받기를 선언한 커넥터다. 번호와 서버 이름은 {@link #DEMO_MANIFEST} 와 같다. */
    private static final ConnectorManifest VISION_MANIFEST = new ConnectorManifest(
            DEMO,
            DEMO_MANIFEST.title(),
            DEMO_MANIFEST.description(),
            DEMO_MANIFEST.fields(),
            "list_scopes",
            "demo",
            List.of("vision"),
            true,
            1,
            List.of());
    /** 형식이 없는 비밀 칸 하나만 가진 커넥터다. 비밀값의 길이 경계를 보는 데 쓴다. */
    private static final ConnectorManifest PIN_MANIFEST = new ConnectorManifest(
            PIN,
            "검사용 번호",
            "",
            List.of(new ConnectorField("pin", "DEMO_PIN", "번호", "", true, true, null, null)),
            "check_pin",
            "pin",
            List.of(),
            false,
            1,
            List.of());
    /** 도구마다 정책을 선언한 커넥터다. 번호와 칸과 서버 이름은 {@link #DEMO_MANIFEST} 와 같다. */
    private static final ConnectorManifest POLICY_MANIFEST = policyManifest(List.of(
            new ConnectorTool("list_scopes", "READ", "none", null, null),
            new ConnectorTool("write_note", "WRITE", "required", "메모 쓰기", null),
            new ConnectorTool("purge_notes", "DESTRUCTIVE", "always", null, null)));

    @Autowired
    ConnectorConnectionService service;

    @Autowired
    ConnectorBindingService bindingService;

    @Autowired
    ConnectorConnectionRepository connections;

    @Autowired
    ConnectorBindingRepository bindings;

    @Autowired
    ConnectorActionService approvals;

    @Autowired
    AgentRepository agents;

    @Autowired
    PlatformTransactionManager transactionManager;

    @MockitoSpyBean
    AppUserRepository users;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    HermesConnectorClient connector;

    @MockitoBean
    HermesToolsetClient toolsets;

    @BeforeEach
    void setUp() {
        when(toolsets.readEnabled(anyString(), anyString())).thenReturn(List.of());
        when(connector.readCatalog()).thenReturn(List.of(DEMO_MANIFEST, PIN_MANIFEST));
        when(connector.call(anyString(), anyString(), anyMap()))
                .thenReturn(CallResult.success(MAPPER.readTree(
                        "{\"scopes\":[{\"id\":\"a\",\"name\":\"범위 A\"},{\"id\":\"b\",\"name\":\"범위 B\"}]}")));
        when(connector.callWithVault(anyString(), anyString(), anyString()))
                .thenReturn(CallResult.success(MAPPER.readTree("{\"scopes\":[]}")));
        when(connector.putConnector(anyString(), anyString(), anyBoolean()))
                .thenReturn(new InstallResult(false, false));
        when(connector.bindConnector(anyString(), anyString(), anyString()))
                .thenReturn(new InstallResult(false, false));
        when(connector.unbindConnector(anyString(), anyString())).thenReturn(new InstallResult(false, false));
        when(connector.readConnector(anyString(), anyString())).thenReturn(state(HermesConnectorClient.MODE_BIND));
        when(connector.probe(anyString(), anyString())).thenReturn(new ProbeResult(true, List.of("list_scopes")));
    }

    @AfterEach
    void tearDown() {
        bindings.deleteAll();
        connections.deleteAll();
        agents.deleteAll();
        users.deleteAll();
    }

    @Test
    @DisplayName("등록은 확인, 잠금, 보관 파일 순서로 반영하고 에이전트를 만들지 않으며 READY 다")
    void registrationVerifiesThenLocksThenWritesVaultWithoutCreatingAgent() {
        CurrentUser user = user(UserRole.MEMBER, 1L);

        ConnectionSnapshot registered = service.register(user, DEMO, VALUES);

        InOrder order = inOrder(connector, users);
        order.verify(connector).call(DEMO, "list_scopes", VALUES);
        order.verify(users).findByIdForUpdate(user.id());
        order.verify(connector).putVault(stored(user).vault(), DEMO, VALUES);
        assertThat(registered.status()).isEqualTo(ConnectionStatus.READY);
        assertThat(registered.bindings()).isEmpty();
        assertThat(stored(user).vaultStored()).isTrue();
        assertThat(agents.count()).isZero();
        verify(connector, never()).putEnv(anyString(), anyString(), anyString());
        verify(connector, never()).putConnector(anyString(), anyString(), anyBoolean());
        verify(toolsets, never()).writeApiServer(any(), any(), any());
    }

    @Test
    @DisplayName("보관 파일 이름은 연결마다 다르고 두 사용자는 자기 연결만 읽는다")
    void eachUserHasOwnConnectionAndVault() {
        CurrentUser first = user(UserRole.MEMBER, 1L);
        CurrentUser second = user(UserRole.MEMBER, 1L);
        Map<String, String> firstValues = Map.of("token", TOKEN, "scope", "a");
        Map<String, String> secondValues = Map.of("token", OTHER_TOKEN, "scope", "a");

        ConnectionSnapshot firstResult = service.register(first, DEMO, firstValues);
        ConnectionSnapshot secondResult = service.register(second, DEMO, secondValues);

        assertThat(stored(first).vault()).isNotEqualTo(stored(second).vault());
        verify(connector).putVault(stored(first).vault(), DEMO, firstValues);
        verify(connector).putVault(stored(second).vault(), DEMO, secondValues);
        assertThat(service.read(second, DEMO).secretPrefixes()).isEqualTo(Map.of("token", "demo"));
        assertThat(firstResult.values()).isEqualTo(secondResult.values()).isEqualTo(Map.of("scope", "a"));
    }

    @Test
    @DisplayName("한 사용자는 커넥터마다 연결과 보관 파일을 따로 갖는다")
    void oneUserHasSeparateConnectionPerConnector() {
        CurrentUser user = user(UserRole.MEMBER, 1L);

        service.register(user, DEMO, VALUES);
        service.register(user, PIN, Map.of("pin", "123456789"));

        assertThat(connections.findAll()).extracting(ConnectorConnection::vault).doesNotHaveDuplicates();
        assertThat(service.catalog(user))
                .extracting(ConnectorSummary::id, ConnectorSummary::myStatus)
                .containsExactly(tuple(DEMO, ConnectionStatus.READY), tuple(PIN, ConnectionStatus.READY));
    }

    @Test
    @DisplayName("값을 바꾸면 붙은 바인딩마다 설치를 다시 보내고 그 바인딩은 재시작 대기 PENDING 이다")
    void reRegistrationReinstallsEveryBindingAsRestartPending() {
        CurrentUser user = user(UserRole.MEMBER, 1L);
        service.register(user, DEMO, VALUES);
        ConnectorConnection connection = stored(user);
        Agent ordinary = agent(user, false);
        ConnectorBinding bound = readyBinding(ordinary, connection, "demo");
        Agent legacy = agent(user, true);
        ConnectorBinding legacyBound = readyBinding(legacy, connection, null);
        when(connector.bindConnector(anyString(), anyString(), anyString())).thenReturn(new InstallResult(true, false));
        when(connector.putConnector(anyString(), anyString(), anyBoolean())).thenReturn(new InstallResult(true, false));
        Map<String, String> replaced = Map.of("token", OTHER_TOKEN);

        ConnectionSnapshot registered = service.register(user, DEMO, replaced);

        verify(connector).putVault(connection.vault(), DEMO, replaced);
        verify(connector).bindConnector(ordinary.hermesProfile(), DEMO, connection.vault());
        // 옛 바인딩은 받은 값을 지금처럼 그 profile 의 env 에 쓴다.
        verify(connector).putEnv(legacy.hermesProfile(), "DEMO_TOKEN", OTHER_TOKEN);
        verify(connector).deleteEnv(legacy.hermesProfile(), "DEMO_SCOPE");
        verify(connector).putConnector(legacy.hermesProfile(), DEMO, true);
        assertThat(registered.status()).isEqualTo(ConnectionStatus.READY);
        assertThat(registered.bindings())
                .extracting(BoundAgentSummary::agentCode, BoundAgentSummary::status, BoundAgentSummary::restartRequired)
                .containsExactlyInAnyOrder(
                        tuple(ordinary.code(), BindingStatus.PENDING, true),
                        tuple(legacy.code(), BindingStatus.PENDING, true));
        assertThat(bindings.findById(bound.id()).orElseThrow().restartRequiredSince())
                .isNotNull();
        assertThat(agentEnabled(ordinary)).as("일반 에이전트").isTrue();
        assertThat(agentEnabled(legacy)).as("옛 커넥터 에이전트").isFalse();
        assertThat(bindings.findById(legacyBound.id()).orElseThrow().desiredEnabled())
                .isTrue();
    }

    @Test
    @DisplayName("바인딩 하나의 다시 설치가 실패해도 연결은 저장되고 그 바인딩만 PENDING 이다")
    void failedReinstallLeavesOnlyThatBindingPending() {
        CurrentUser user = user(UserRole.MEMBER, 1L);
        service.register(user, DEMO, VALUES);
        ConnectorConnection connection = stored(user);
        Agent broken = agent(user, false);
        Agent fine = agent(user, false);
        ConnectorBinding brokenBinding = readyBinding(broken, connection, "demo");
        ConnectorBinding fineBinding = readyBinding(fine, connection, "demo");
        doThrow(new IllegalStateException())
                .when(connector)
                .bindConnector(eq(broken.hermesProfile()), anyString(), anyString());
        when(connector.bindConnector(eq(fine.hermesProfile()), anyString(), anyString()))
                .thenReturn(new InstallResult(true, false));

        ConnectionSnapshot registered = service.register(user, DEMO, Map.of("token", OTHER_TOKEN));

        assertThat(registered.status()).isEqualTo(ConnectionStatus.READY);
        assertThat(registered.secretPrefixes()).isEqualTo(Map.of("token", "demo"));
        ConnectorBinding failed = bindings.findById(brokenBinding.id()).orElseThrow();
        assertThat(failed.status()).isEqualTo(BindingStatus.PENDING);
        assertThat(failed.desiredEnabled()).isFalse();
        ConnectorBinding reinstalled = bindings.findById(fineBinding.id()).orElseThrow();
        assertThat(reinstalled.status()).isEqualTo(BindingStatus.PENDING);
        assertThat(reinstalled.restartRequired()).isTrue();
    }

    @Test
    @DisplayName("보관 파일을 쓰지 못하면 PENDING 을 커밋하고 연결 실패로 끝나며 응답과 로그에 비밀 값이 없다")
    void vaultFailureCommitsPendingWithoutLeakingSecret(CapturedOutput output) {
        CurrentUser user = user(UserRole.MEMBER, 1L);
        doThrow(new IllegalStateException("echo " + TOKEN))
                .when(connector)
                .putVault(anyString(), anyString(), anyMap());

        assertThatThrownBy(() -> service.register(user, DEMO, VALUES))
                .isInstanceOf(ConnectorOperationFailure.class)
                .satisfies(error -> assertThat(error.toString()).doesNotContain(TOKEN));

        ConnectorConnection stored = stored(user);
        assertThat(stored.status()).isEqualTo(ConnectionStatus.PENDING);
        assertThat(stored.vaultStored()).isFalse();
        assertThat(stored.fields().secretPrefixes()).isEmpty();
        assertThat(service.read(user, DEMO).toString()).doesNotContain(TOKEN);
        assertThat(output.getAll()).contains("failed at vault").doesNotContain(TOKEN);
    }

    @Test
    @DisplayName("확인 도구가 실패하면 공통 어휘의 오류 코드로 끝나고 아무것도 저장하지 않는다")
    void verifyFailureMapsToErrorCodeAndStoresNothing() {
        CurrentUser user = user(UserRole.MEMBER, 1L);
        Map<String, String> values = Map.of("token", BAD_TOKEN);

        assertVerifyFailure(
                user, values, ConnectorCallError.CREDENTIAL_REJECTED, ErrorCode.CONNECTOR_CREDENTIAL_REJECTED);
        assertVerifyFailure(user, values, ConnectorCallError.FORBIDDEN, ErrorCode.CONNECTOR_FORBIDDEN);
        assertVerifyFailure(user, values, ConnectorCallError.INVALID_INPUT, ErrorCode.VALIDATION_FAILED);
        assertVerifyFailure(user, values, ConnectorCallError.UNAVAILABLE, ErrorCode.CONNECTOR_UNAVAILABLE);
        doThrow(new IllegalStateException()).when(connector).call(anyString(), anyString(), anyMap());
        assertCode(() -> service.register(user, DEMO, values), ErrorCode.CONNECTOR_UNAVAILABLE);

        assertThat(connections.count()).isZero();
        verify(users, never()).findByIdForUpdate(anyLong());
        verify(connector, never()).putVault(anyString(), anyString(), anyMap());
    }

    @Test
    @DisplayName("모르는 키, 필수 칸 누락, 형식 불일치는 확인 도구를 부르기 전에 거절한다")
    void rejectsUnknownKeyMissingRequiredAndPatternMismatchBeforeVerify() {
        CurrentUser user = user(UserRole.MEMBER, 1L);

        assertCode(
                () -> service.register(user, DEMO, Map.of("token", TOKEN, "other", "x")), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.register(user, DEMO, Map.of("scope", "a")), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.register(user, DEMO, Map.of("token", " ")), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.register(user, DEMO, null), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.register(user, DEMO, Map.of("token", "demo_ok_123")), ErrorCode.VALIDATION_FAILED);
        assertCode(
                () -> service.register(user, DEMO, Map.of("token", TOKEN, "scope", "a\nOTHER=b")),
                ErrorCode.VALIDATION_FAILED);

        verify(connector, never()).call(anyString(), anyString(), anyMap());
        assertThat(connections.count()).isZero();
    }

    @Test
    @DisplayName("카탈로그에 없고 연결도 없는 커넥터는 CONNECTOR_NOT_FOUND 다")
    void unknownConnectorIsNotFound() {
        CurrentUser user = user(UserRole.MEMBER, 1L);

        assertCode(() -> service.read(user, "no-such"), ErrorCode.CONNECTOR_NOT_FOUND);
        assertCode(() -> service.register(user, "no-such", VALUES), ErrorCode.CONNECTOR_NOT_FOUND);
        assertCode(() -> service.options(user, "no-such", "scope", VALUES), ErrorCode.CONNECTOR_NOT_FOUND);
        assertCode(() -> service.check(user, "no-such"), ErrorCode.CONNECTOR_NOT_FOUND);
        assertCode(() -> service.disconnect(user, "no-such"), ErrorCode.CONNECTOR_NOT_FOUND);
        assertCode(() -> service.disconnect(user, DEMO), ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("등록 전의 연결은 빈 DISCONNECTED 로 읽힌다")
    void readsEmptyDisconnectedBeforeRegistration() {
        CurrentUser user = user(UserRole.MEMBER, 1L);

        ConnectionSnapshot read = service.read(user, DEMO);

        assertThat(read)
                .isEqualTo(new ConnectionSnapshot(
                        DEMO, ConnectionStatus.DISCONNECTED, Map.of(), Map.of(), null, List.of(), 0));
    }

    @Test
    @DisplayName("16자 이상인 비밀 칸은 앞 4자만 저장하고 DB 의 fields 에 원문이 없다")
    void storesOnlyFirstFourCharactersOfSecretFields() throws Exception {
        CurrentUser user = user(UserRole.MEMBER, 1L);

        ConnectionSnapshot registered = service.register(user, DEMO, Map.of("token", TOKEN, "scope", "a"));

        assertThat(registered.secretPrefixes()).isEqualTo(Map.of("token", "demo"));
        assertThat(registered.values()).isEqualTo(Map.of("scope", "a"));
        String column = fieldsColumn(user, DEMO);
        assertThat(column).doesNotContain(TOKEN);
        assertThat(MAPPER.readTree(column))
                .isEqualTo(MAPPER.readTree("{\"values\":{\"scope\":\"a\"},\"secretPrefixes\":{\"token\":\"demo\"}}"));
    }

    @Test
    @DisplayName("15자 비밀값은 앞부분도 저장하지 않고 16자는 앞 4자를 저장한다")
    void doesNotStorePrefixOfFifteenCharacterSecretButStoresSixteenCharacterPrefix() {
        CurrentUser fifteen = user(UserRole.MEMBER, 1L);
        CurrentUser sixteen = user(UserRole.MEMBER, 1L);
        String secret15 = "abcdefghijklmno";
        String secret16 = "abcdefghijklmnop";

        ConnectionSnapshot short15 = service.register(fifteen, PIN, Map.of("pin", secret15));
        ConnectionSnapshot long16 = service.register(sixteen, PIN, Map.of("pin", secret16));

        assertThat(short15.secretPrefixes()).isEmpty();
        assertThat(fieldsColumn(fifteen, PIN)).doesNotContain("abcd");
        assertThat(long16.secretPrefixes()).isEqualTo(Map.of("pin", "abcd"));
        assertThat(fieldsColumn(sixteen, PIN)).doesNotContain("abcde");
    }

    @Test
    @DisplayName("앞 4자 자리에서 대리 쌍이 갈리는 비밀값은 그 앞 3자만 저장한다")
    void cutsSecretPrefixBeforeSplitSurrogatePair() {
        CurrentUser user = user(UserRole.MEMBER, 1L);
        // 넷째와 다섯째 char 가 한 글자의 대리 쌍이다.
        String secret = "abc" + new String(Character.toChars(0x1F600)) + "0123456789ab";

        ConnectionSnapshot registered = service.register(user, PIN, Map.of("pin", secret));

        assertThat(registered.secretPrefixes()).isEqualTo(Map.of("pin", "abc"));
    }

    @Test
    @DisplayName("비밀 칸은 4096자까지 받고 넘으면 확인 도구를 부르기 전에 거절한다")
    void acceptsSecretUpTo4096CharactersAndRejectsLongerBeforeVerify() {
        CurrentUser user = user(UserRole.MEMBER, 1L);

        assertCode(() -> service.register(user, PIN, Map.of("pin", "1".repeat(4097))), ErrorCode.VALIDATION_FAILED);
        verify(connector, never()).call(anyString(), anyString(), anyMap());
        assertThat(connections.count()).isZero();

        ConnectionSnapshot registered = service.register(user, PIN, Map.of("pin", "1".repeat(4096)));

        assertThat(registered.secretPrefixes()).isEqualTo(Map.of("pin", "1111"));
    }

    @Test
    @DisplayName("저장할 칸 값이 fields 열 크기를 넘으면 외부에 반영하기 전에 거절하고 딱 맞으면 저장한다")
    void rejectsFieldsLongerThanColumnBeforeExternalCallsAndStoresExactFit() {
        CurrentUser user = user(UserRole.MEMBER, 1L);
        List<ConnectorField> fields = new ArrayList<>();
        Map<String, String> values = new LinkedHashMap<>();
        for (int index = 1; index <= 8; index++) {
            fields.add(new ConnectorField("k" + index, "DEMO_K" + index, "칸", "", false, true, null, null));
            values.put("k" + index, "v".repeat(500));
        }
        when(connector.readCatalog())
                .thenReturn(List.of(new ConnectorManifest(
                        "demo-wide", "넓은 칸", "", fields, "check", "wide", List.of(), false, 1, List.of())));
        // {"values":{"k1":"…",…},"secretPrefixes":{}} 에서 값 말고 드는 글자 수는 33 + 칸마다 7 + 쉼표 7 이다.
        int lastLength = 4000 - (33 + 8 * 7 + 7) - 7 * 500;
        values.put("k8", "v".repeat(lastLength + 1));

        assertCode(() -> service.register(user, "demo-wide", values), ErrorCode.VALIDATION_FAILED);
        verify(connector, never()).call(anyString(), anyString(), anyMap());
        verify(connector, never()).putVault(anyString(), anyString(), anyMap());
        assertThat(connections.count()).isZero();

        values.put("k8", "v".repeat(lastLength));
        service.register(user, "demo-wide", values);

        assertThat(fieldsColumn(user, "demo-wide")).hasSize(4000);
    }

    @Test
    @DisplayName("카탈로그는 칸 선언과 내 상태와 그 연결이 붙은 에이전트를 주고 등록 전에는 DISCONNECTED 다")
    void catalogListsFieldsMyStatusAndBoundAgents() {
        CurrentUser user = user(UserRole.MEMBER, 1L);

        ConnectorSummary before = service.catalog(user).get(0);

        assertThat(before.id()).isEqualTo(DEMO);
        assertThat(before.title()).isEqualTo("검사용 메모");
        assertThat(before.myStatus()).isEqualTo(ConnectionStatus.DISCONNECTED);
        assertThat(before.available()).isTrue();
        assertThat(before.bindings()).isEmpty();
        assertThat(before.fields().get(0).key()).isEqualTo("token");
        assertThat(before.fields().get(0).secret()).isTrue();
        assertThat(before.fields().get(0).hasOptions()).isFalse();
        assertThat(before.fields().get(1).hasOptions()).isTrue();
        assertThat(before.fields().get(1).autoSelectSingle()).isTrue();
        assertThat(before.toString()).doesNotContain("DEMO_TOKEN", "DEMO_SCOPE", "list_scopes");

        service.register(user, DEMO, VALUES);
        Agent agent = agent(user, false);
        readyBinding(agent, stored(user), "demo");

        assertThat(service.catalog(user).get(0).bindings())
                .containsExactly(new BoundAgentSummary(agent.code(), agent.name(), BindingStatus.READY, false));
        assertThat(service.read(user, DEMO).bindings())
                .containsExactly(new BoundAgentSummary(agent.code(), agent.name(), BindingStatus.READY, false));
    }

    @Test
    @DisplayName("카탈로그에서 빠졌지만 해제하지 않은 내 연결은 커넥터 번호를 이름으로 쓴 쓸 수 없는 항목으로 함께 나온다")
    void catalogAppendsRemovedButConnectedConnectorAsUnavailable() {
        CurrentUser user = user(UserRole.MEMBER, 1L);
        CurrentUser other = user(UserRole.MEMBER, 1L);
        service.register(user, DEMO, VALUES);
        service.register(other, PIN, Map.of("pin", "123456789"));
        when(connector.readCatalog()).thenReturn(List.of(PIN_MANIFEST));

        List<ConnectorSummary> listed = service.catalog(user);

        assertThat(listed)
                .containsExactly(
                        new ConnectorSummary(
                                PIN,
                                "검사용 번호",
                                "",
                                service.catalog(other).get(0).fields(),
                                List.of(),
                                ConnectionStatus.DISCONNECTED,
                                true,
                                List.of()),
                        new ConnectorSummary(
                                DEMO, DEMO, "", List.of(), List.of(), ConnectionStatus.READY, false, List.of()));
    }

    @Test
    @DisplayName("카탈로그에서 빠진 연결을 해제하면 목록에서 사라진다")
    void catalogDropsRemovedConnectorOnceDisconnected() {
        CurrentUser user = user(UserRole.MEMBER, 1L);
        service.register(user, DEMO, VALUES);
        when(connector.readCatalog()).thenReturn(List.of());
        assertThat(service.catalog(user)).extracting(ConnectorSummary::id).containsExactly(DEMO);

        service.disconnect(user, DEMO);

        assertThat(service.catalog(user)).isEmpty();
    }

    @Test
    @DisplayName("확인 도구는 트랜잭션 밖에서 부르고 그 사이에 끼어든 해제 뒤에도 등록의 상태 전이가 지켜진다")
    void verifyRunsOutsideTransactionAndInterleavedDisconnectKeepsTransitionRules() {
        CurrentUser user = user(UserRole.MEMBER, 1L);
        service.register(user, DEMO, VALUES);
        List<Boolean> transactionActive = new ArrayList<>();
        Map<String, String> replaced = Map.of("token", OTHER_TOKEN);
        when(connector.call(DEMO, "list_scopes", replaced)).thenAnswer(invocation -> {
            transactionActive.add(TransactionSynchronizationManager.isActualTransactionActive());
            // 확인이 도는 동안 같은 사용자의 해제가 먼저 끝난다.
            assertThat(service.disconnect(user, DEMO).status()).isEqualTo(ConnectionStatus.DISCONNECTED);
            return CallResult.success(MAPPER.readTree("{\"scopes\":[]}"));
        });

        ConnectionSnapshot registered = service.register(user, DEMO, replaced);

        assertThat(transactionActive).containsExactly(false);
        assertThat(registered.status()).isEqualTo(ConnectionStatus.READY);
        ConnectorConnection stored = stored(user);
        assertThat(stored.status()).isEqualTo(ConnectionStatus.READY);
        assertThat(stored.vaultStored()).isTrue();
        assertThat(stored.fields().secretPrefixes()).isEqualTo(Map.of("token", "demo"));
        assertThat(connections.count()).isEqualTo(1);
        InOrder order = inOrder(connector);
        order.verify(connector).deleteVault(stored.vault());
        order.verify(connector).putVault(stored.vault(), DEMO, replaced);
    }

    @Test
    @DisplayName("처음 등록의 확인 사이에 같은 사용자의 다른 등록이 끼어도 연결은 하나다")
    void interleavedFirstRegistrationsShareOneConnection() {
        CurrentUser user = user(UserRole.MEMBER, 1L);
        // 기본 한도는 같은 사용자의 겹친 등록을 거절한다. 동시 한도를 올린 설정에서도 행이 하나임을 본다.
        ConnectorConnectionService relaxed = serviceLimitedTo(2, 1000);
        Map<String, String> outer = Map.of("token", OTHER_TOKEN);
        when(connector.call(DEMO, "list_scopes", outer)).thenAnswer(invocation -> {
            relaxed.register(user, DEMO, VALUES);
            return CallResult.success(MAPPER.readTree("{\"scopes\":[]}"));
        });

        ConnectionSnapshot registered = relaxed.register(user, DEMO, outer);

        assertThat(connections.count()).isEqualTo(1);
        assertThat(agents.count()).isZero();
        assertThat(registered.secretPrefixes()).isEqualTo(Map.of("token", "demo"));
    }

    @Test
    @DisplayName("카탈로그를 읽지 못하면 CONNECTOR_UNAVAILABLE 이다")
    void catalogFailureIsUnavailable() {
        CurrentUser user = user(UserRole.MEMBER, 1L);
        doThrow(new IllegalStateException()).when(connector).readCatalog();

        assertCode(() -> service.catalog(user), ErrorCode.CONNECTOR_UNAVAILABLE);
        assertCode(() -> service.register(user, DEMO, VALUES), ErrorCode.CONNECTOR_UNAVAILABLE);
    }

    @Test
    @DisplayName("선택지는 작성 중인 값으로 도구를 불러 항목을 꺼내고 아무것도 저장하지 않는다")
    void optionsCallToolWithCandidateValuesAndStoreNothing() {
        CurrentUser user = user(UserRole.MEMBER, 1L);

        List<ConnectorOption> options = service.options(user, DEMO, "scope", VALUES);

        assertThat(options).containsExactly(new ConnectorOption("a", "범위 A"), new ConnectorOption("b", "범위 B"));
        verify(connector).call(DEMO, "list_scopes", VALUES);
        assertThat(connections.count()).isZero();
    }

    @Test
    @DisplayName("선택지가 없는 칸과 선택지 도구의 실패는 정해진 오류 코드로 끝난다")
    void optionsRejectFieldWithoutOptionsAndMapToolFailure() {
        CurrentUser user = user(UserRole.MEMBER, 1L);

        assertCode(() -> service.options(user, DEMO, "token", VALUES), ErrorCode.VALIDATION_FAILED);
        verify(connector, never()).call(anyString(), anyString(), anyMap());

        when(connector.call(anyString(), anyString(), anyMap()))
                .thenReturn(CallResult.failure(ConnectorCallError.CREDENTIAL_REJECTED));
        assertCode(() -> service.options(user, DEMO, "scope", VALUES), ErrorCode.CONNECTOR_CREDENTIAL_REJECTED);

        when(connector.call(anyString(), anyString(), anyMap()))
                .thenReturn(CallResult.success(MAPPER.readTree("{\"other\":[]}")));
        assertCode(() -> service.options(user, DEMO, "scope", VALUES), ErrorCode.CONNECTOR_UNAVAILABLE);
    }

    @Test
    @DisplayName("해제는 붙은 바인딩을 모두 뗀 뒤 보관 파일을 지우고 칸 값을 비운다")
    void disconnectDetachesEveryBindingThenDeletesVaultAndClearsFields() {
        CurrentUser user = user(UserRole.MEMBER, 1L);
        service.register(user, DEMO, Map.of("token", TOKEN, "scope", "a"));
        ConnectorConnection connection = stored(user);
        Agent ordinary = agent(user, false);
        readyBinding(ordinary, connection, "demo");
        Agent legacy = agent(user, true);
        readyBinding(legacy, connection, null);

        ConnectionSnapshot disconnected = service.disconnect(user, DEMO);

        InOrder ordinaryOrder = inOrder(connector);
        ordinaryOrder.verify(connector).unbindConnector(ordinary.hermesProfile(), DEMO);
        ordinaryOrder.verify(connector).deleteVault(connection.vault());
        // 옛 바인딩은 지금처럼 env 를 지우고 설치를 끈다.
        InOrder legacyOrder = inOrder(connector);
        legacyOrder.verify(connector).deleteEnv(legacy.hermesProfile(), "DEMO_TOKEN");
        legacyOrder.verify(connector).deleteEnv(legacy.hermesProfile(), "DEMO_SCOPE");
        legacyOrder.verify(connector).putConnector(legacy.hermesProfile(), DEMO, false);
        legacyOrder.verify(connector).deleteVault(connection.vault());
        assertThat(bindings.count()).isZero();
        assertThat(disconnected.status()).isEqualTo(ConnectionStatus.DISCONNECTED);
        assertThat(disconnected.bindings()).isEmpty();
        assertThat(disconnected.secretPrefixes()).isEmpty();
        assertThat(disconnected.values()).isEmpty();
        assertThat(MAPPER.readTree(fieldsColumn(user, DEMO)))
                .isEqualTo(MAPPER.readTree("{\"values\":{},\"secretPrefixes\":{}}"));
        assertThat(stored(user).vaultStored()).isFalse();
        assertThat(agentEnabled(ordinary)).as("일반 에이전트").isTrue();
        assertThat(agentEnabled(legacy)).as("옛 커넥터 에이전트").isFalse();
    }

    @Test
    @DisplayName("카탈로그에서 빠진 커넥터는 읽기와 해제만 되고 옛 바인딩은 env 를 지우지 않고 설치만 끈다")
    void removedConnectorCanOnlyBeReadAndDisconnectedWithoutEnvDeletion() {
        CurrentUser user = user(UserRole.MEMBER, 1L);
        service.register(user, DEMO, VALUES);
        Agent legacy = agent(user, true);
        readyBinding(legacy, stored(user), null);
        when(connector.readCatalog()).thenReturn(List.of(PIN_MANIFEST));

        assertThat(service.read(user, DEMO).secretPrefixes()).isEqualTo(Map.of("token", "demo"));
        assertCode(() -> service.register(user, DEMO, VALUES), ErrorCode.CONNECTOR_NOT_FOUND);
        assertCode(() -> service.options(user, DEMO, "scope", VALUES), ErrorCode.CONNECTOR_NOT_FOUND);

        ConnectionSnapshot disconnected = service.disconnect(user, DEMO);

        verify(connector, never()).deleteEnv(anyString(), anyString());
        verify(connector).putConnector(legacy.hermesProfile(), DEMO, false);
        assertThat(disconnected.status()).isEqualTo(ConnectionStatus.DISCONNECTED);
        assertThat(service.read(user, DEMO).status()).isEqualTo(ConnectionStatus.DISCONNECTED);
    }

    @Test
    @DisplayName("보관 파일을 지우지 못하면 바인딩은 떼어진 채 PENDING 이고 다시 해제하면 끝난다")
    void vaultDeleteFailureLeavesPendingUntilRetried() {
        CurrentUser user = user(UserRole.MEMBER, 1L);
        service.register(user, DEMO, VALUES);
        readyBinding(agent(user, false), stored(user), "demo");
        doThrow(new IllegalStateException()).when(connector).deleteVault(anyString());

        assertThatThrownBy(() -> service.disconnect(user, DEMO)).isInstanceOf(ConnectorOperationFailure.class);

        assertThat(stored(user).status()).isEqualTo(ConnectionStatus.PENDING);
        assertThat(bindings.count()).isZero();

        doReturn(true).when(connector).deleteVault(anyString());

        assertThat(service.disconnect(user, DEMO).status()).isEqualTo(ConnectionStatus.DISCONNECTED);
    }

    @Test
    @DisplayName("연결 확인은 보관 파일의 값으로 확인 도구를 트랜잭션 밖에서 부르고 READY 다")
    void checkVerifiesWithVaultOutsideTransaction() {
        CurrentUser user = user(UserRole.MEMBER, 1L);
        service.register(user, DEMO, VALUES);
        List<Boolean> transactionActive = new ArrayList<>();
        when(connector.callWithVault(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            transactionActive.add(TransactionSynchronizationManager.isActualTransactionActive());
            return CallResult.success(MAPPER.readTree("{\"scopes\":[]}"));
        });

        ConnectionSnapshot checked = service.check(user, DEMO);

        assertThat(transactionActive).containsExactly(false);
        verify(connector).callWithVault(DEMO, "list_scopes", stored(user).vault());
        assertThat(checked.status()).isEqualTo(ConnectionStatus.READY);
    }

    @Test
    @DisplayName("연결 확인의 확인 도구가 실패하면 PENDING 을 커밋하고 공통 어휘의 오류로 끝나며 바인딩은 건드리지 않는다")
    void checkVerifyFailureCommitsPendingAndMapsError() {
        CurrentUser user = user(UserRole.MEMBER, 1L);
        service.register(user, DEMO, VALUES);
        readyBinding(agent(user, false), stored(user), "demo");
        when(connector.callWithVault(anyString(), anyString(), anyString()))
                .thenReturn(CallResult.failure(ConnectorCallError.CREDENTIAL_REJECTED));

        assertCode(() -> service.check(user, DEMO), ErrorCode.CONNECTOR_CREDENTIAL_REJECTED);

        assertThat(stored(user).status()).isEqualTo(ConnectionStatus.PENDING);
        assertThat(bindings.findAll()).extracting(ConnectorBinding::status).containsExactly(BindingStatus.READY);
        verify(connector, never()).bindConnector(anyString(), anyString(), anyString());

        doThrow(new IllegalStateException()).when(connector).callWithVault(anyString(), anyString(), anyString());
        assertCode(() -> service.check(user, DEMO), ErrorCode.CONNECTOR_UNAVAILABLE);
    }

    @Test
    @DisplayName("옛 연결의 확인은 옛 에이전트의 profile 에서 보관 파일로 옮긴 뒤 확인 도구를 부르고 서버 이름을 채운다")
    void legacyCheckImportsVaultThenVerifies() {
        CurrentUser user = user(UserRole.MEMBER, 1L);
        Agent legacy = agent(user, true);
        ConnectorConnection connection = legacyConnection(user);
        ConnectorBinding binding = readyBinding(legacy, connection, null);
        when(connector.readConnector(anyString(), anyString())).thenReturn(state(HermesConnectorClient.MODE_ISOLATED));

        ConnectionSnapshot checked = service.check(user, DEMO);

        InOrder order = inOrder(connector);
        order.verify(connector).importVault(connection.vault(), DEMO, legacy.hermesProfile());
        order.verify(connector).callWithVault(DEMO, "list_scopes", connection.vault());
        order.verify(connector).putConnector(legacy.hermesProfile(), DEMO, true);
        assertThat(checked.status()).isEqualTo(ConnectionStatus.READY);
        assertThat(stored(user).vaultStored()).isTrue();
        ConnectorBinding resynced = bindings.findById(binding.id()).orElseThrow();
        assertThat(resynced.mcpServer()).isEqualTo("demo");
        assertThat(resynced.status()).isEqualTo(BindingStatus.READY);
        assertThat(agentEnabled(legacy)).isTrue();
        verify(connector, never()).bindConnector(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("옛 연결의 값을 옮기지 못하면 확인 도구를 부르지 않고 연결의 상태를 그대로 둔다")
    void legacyCheckKeepsStateWhenImportFails() {
        CurrentUser user = user(UserRole.MEMBER, 1L);
        Agent legacy = agent(user, true);
        legacyConnection(user);
        readyBinding(legacy, stored(user), null);
        when(connector.readConnector(anyString(), anyString())).thenReturn(state(HermesConnectorClient.MODE_ISOLATED));
        doThrow(new IllegalStateException()).when(connector).importVault(anyString(), anyString(), anyString());

        ConnectionSnapshot checked = service.check(user, DEMO);

        verify(connector, never()).callWithVault(anyString(), anyString(), anyString());
        assertThat(checked.status()).isEqualTo(ConnectionStatus.READY);
        assertThat(stored(user).vaultStored()).isFalse();
    }

    @Test
    @DisplayName("붙은 바인딩은 연결 확인이 설치를 다시 보내 바인딩 방식으로 설치되고 probe 가 도구를 낼 때만 READY 다")
    void checkResyncsBoundBindingByModeAndProbe() {
        CurrentUser user = user(UserRole.MEMBER, 1L);
        service.register(user, DEMO, VALUES);
        Agent agent = agent(user, false);
        ConnectorBinding binding = pendingBinding(agent, stored(user));

        assertThat(service.check(user, DEMO).bindings())
                .extracting(BoundAgentSummary::status)
                .containsExactly(BindingStatus.READY);
        verify(connector)
                .bindConnector(agent.hermesProfile(), DEMO, stored(user).vault());
        verify(connector).probe(agent.hermesProfile(), "demo");

        when(connector.readConnector(anyString(), anyString())).thenReturn(state(HermesConnectorClient.MODE_ISOLATED));
        assertThat(service.check(user, DEMO).bindings())
                .extracting(BoundAgentSummary::status)
                .containsExactly(BindingStatus.PENDING);

        when(connector.readConnector(anyString(), anyString())).thenReturn(state(HermesConnectorClient.MODE_BIND));
        when(connector.probe(anyString(), anyString())).thenReturn(new ProbeResult(true, List.of()));
        assertThat(service.check(user, DEMO).bindings())
                .extracting(BoundAgentSummary::status)
                .containsExactly(BindingStatus.PENDING);
        // 켜진 내장 도구는 일반 바인딩의 판정에 들지 않는다.
        verify(toolsets, never()).readEnabled(anyString(), anyString());
        assertThat(bindings.findById(binding.id()).orElseThrow().restartRequired())
                .isFalse();
    }

    @Test
    @DisplayName("재시작 대기 바인딩은 연결 확인에서 설치를 다시 보내지 않고 PENDING 이다")
    void checkDoesNotReinstallRestartPendingBinding() {
        CurrentUser user = user(UserRole.MEMBER, 1L);
        service.register(user, DEMO, VALUES);
        ConnectorBinding binding = ConnectorBinding.pending(agent(user, false), stored(user), "demo", NOW);
        binding.installed(true, NOW);
        bindings.save(binding);

        ConnectionSnapshot checked = service.check(user, DEMO);

        verify(connector, never()).bindConnector(anyString(), anyString(), anyString());
        assertThat(checked.bindings())
                .extracting(BoundAgentSummary::status, BoundAgentSummary::restartRequired)
                .containsExactly(tuple(BindingStatus.PENDING, true));
    }

    @Test
    @DisplayName("재시작 대기 바인딩의 서버 이름이 비었으면 연결 확인이 설치를 다시 보내지 않고 manifest 의 이름으로 채운다")
    void checkRecordsServerNameOfRestartPendingBinding() {
        CurrentUser user = user(UserRole.MEMBER, 1L);
        service.register(user, DEMO, VALUES);
        ConnectorBinding binding = ConnectorBinding.pending(agent(user, false), stored(user), null, NOW);
        binding.installed(true, NOW);
        bindings.save(binding);

        service.check(user, DEMO);

        ConnectorBinding checked = bindings.findById(binding.id()).orElseThrow();
        assertThat(checked.mcpServer()).isEqualTo("demo");
        assertThat(checked.status()).isEqualTo(BindingStatus.PENDING);
        assertThat(checked.restartRequired()).isTrue();
        verify(connector, never()).bindConnector(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("보관 파일이 없고 옛 바인딩도 없는 연결의 확인은 확인 도구를 부르지 않고 PENDING 을 커밋하며 CONNECTOR_NOT_CONNECTED 다")
    void checkWithoutVaultOrLegacyBindingAsksToRegisterAgain() {
        CurrentUser user = user(UserRole.MEMBER, 1L);
        legacyConnection(user);

        assertCode(() -> service.check(user, DEMO), ErrorCode.CONNECTOR_NOT_CONNECTED);

        verify(connector, never()).importVault(anyString(), anyString(), anyString());
        verify(connector, never()).callWithVault(anyString(), anyString(), anyString());
        assertThat(stored(user).status()).isEqualTo(ConnectionStatus.PENDING);
        assertThat(stored(user).vaultStored()).isFalse();

        // 값을 다시 등록하면 보관 파일이 생기고 확인이 다시 된다.
        service.register(user, DEMO, VALUES);
        assertThat(service.check(user, DEMO).status()).isEqualTo(ConnectionStatus.READY);
    }

    @Test
    @DisplayName("카탈로그에서 빠진 커넥터는 보관 파일과 옛 바인딩이 없어도 확인이 오류 없이 연결과 바인딩을 PENDING 으로 둔다")
    void checkWithoutVaultOfConnectorRemovedFromCatalogEndsPendingWithoutError() {
        CurrentUser user = user(UserRole.MEMBER, 1L);
        legacyConnection(user);
        readyBinding(agent(user, false), stored(user), "demo");
        when(connector.readCatalog()).thenReturn(List.of(PIN_MANIFEST));

        ConnectionSnapshot checked = service.check(user, DEMO);

        verify(connector, never()).callWithVault(anyString(), anyString(), anyString());
        verify(connector, never()).bindConnector(anyString(), anyString(), anyString());
        assertThat(checked.status()).isEqualTo(ConnectionStatus.PENDING);
        assertThat(stored(user).status()).isEqualTo(ConnectionStatus.PENDING);
        assertThat(checked.bindings()).extracting(BoundAgentSummary::status).containsExactly(BindingStatus.PENDING);
    }

    @Test
    @DisplayName("다시 보낸 설치가 바뀐 것이 있다고 답하면 그 시각으로 재시작 대기가 시작된다")
    void checkStartsRestartWaitWhenReinstallChangesSomething() {
        CurrentUser user = user(UserRole.MEMBER, 1L);
        service.register(user, DEMO, VALUES);
        ConnectorBinding binding = pendingBinding(agent(user, false), stored(user));
        when(connector.bindConnector(anyString(), anyString(), anyString())).thenReturn(new InstallResult(true, false));

        ConnectionSnapshot checked = service.check(user, DEMO);

        assertThat(checked.bindings())
                .extracting(BoundAgentSummary::status, BoundAgentSummary::restartRequired)
                .containsExactly(tuple(BindingStatus.PENDING, true));
        verify(connector, never()).probe(anyString(), anyString());
        assertThat(bindings.findById(binding.id()).orElseThrow().restartRequiredSince())
                .isAfter(NOW);
    }

    @Test
    @DisplayName("바인딩 확인의 외부 호출이 실패하면 그 바인딩만 PENDING 을 커밋하고 연결 실패로 끝난다")
    void bindingResyncFailureCommitsPendingAndFails() {
        CurrentUser user = user(UserRole.MEMBER, 1L);
        service.register(user, DEMO, VALUES);
        ConnectorBinding binding = readyBinding(agent(user, false), stored(user), "demo");
        doThrow(new IllegalStateException()).when(connector).probe(anyString(), anyString());

        assertThatThrownBy(() -> service.check(user, DEMO)).isInstanceOf(ConnectorOperationFailure.class);

        assertThat(stored(user).status()).isEqualTo(ConnectionStatus.READY);
        assertThat(bindings.findById(binding.id()).orElseThrow().status()).isEqualTo(BindingStatus.PENDING);
    }

    @Test
    @DisplayName("카탈로그에서 빠진 커넥터는 확인에서 확인 도구와 설치를 부르지 않고 연결과 바인딩이 PENDING 이다")
    void checkDoesNotReinstallConnectorRemovedFromCatalog() {
        CurrentUser user = user(UserRole.MEMBER, 1L);
        service.register(user, DEMO, VALUES);
        readyBinding(agent(user, false), stored(user), "demo");
        when(connector.readCatalog()).thenReturn(List.of(PIN_MANIFEST));

        ConnectionSnapshot checked = service.check(user, DEMO);

        verify(connector, never()).callWithVault(anyString(), anyString(), anyString());
        verify(connector, never()).bindConnector(anyString(), anyString(), anyString());
        assertThat(checked.status()).isEqualTo(ConnectionStatus.PENDING);
        assertThat(checked.bindings()).extracting(BoundAgentSummary::status).containsExactly(BindingStatus.PENDING);
    }

    @Test
    @DisplayName("옛 바인딩은 선언한 toolset 이 켜졌을 때만 READY 이고 그때만 선언한 사진 받기를 옮긴다")
    void legacyBindingKeepsToolsetAndAttachmentJudgement() {
        when(connector.readCatalog()).thenReturn(List.of(VISION_MANIFEST));
        when(connector.readConnector(anyString(), anyString())).thenReturn(state(HermesConnectorClient.MODE_ISOLATED));
        CurrentUser user = user(UserRole.MEMBER, 1L);
        Agent legacy = agent(user, true);
        legacyConnection(user);
        ConnectorBinding binding = readyBinding(legacy, stored(user), null);

        assertThat(service.check(user, DEMO).bindings())
                .extracting(BoundAgentSummary::status)
                .containsExactly(BindingStatus.PENDING);
        assertThat(agents.findById(legacy.id()).orElseThrow().acceptsAttachments())
                .isFalse();

        when(toolsets.readEnabled(anyString(), anyString())).thenReturn(List.of("vision"));

        assertThat(service.check(user, DEMO).bindings())
                .extracting(BoundAgentSummary::status)
                .containsExactly(BindingStatus.READY);
        assertThat(agents.findById(legacy.id()).orElseThrow().acceptsAttachments())
                .isTrue();
        assertThat(bindings.findById(binding.id()).orElseThrow().status()).isEqualTo(BindingStatus.READY);
    }

    @Test
    @DisplayName("도구를 선언한 커넥터는 probe 가 낸 선언 밖 도구를 세고, 다시 등록하면 0 으로 되돌린다")
    void countsUndeclaredToolsAndResetsOnReRegistration() {
        when(connector.readCatalog()).thenReturn(List.of(POLICY_MANIFEST));
        CurrentUser user = user(UserRole.MEMBER, 1L);
        service.register(user, DEMO, VALUES);
        pendingBinding(agent(user, false), stored(user));
        when(connector.probe(anyString(), anyString()))
                .thenReturn(new ProbeResult(true, List.of("list_scopes", "hidden_tool")));

        ConnectionSnapshot checked = service.check(user, DEMO);

        assertThat(checked.undeclaredTools()).isEqualTo(1);
        assertThat(checked.bindings()).extracting(BoundAgentSummary::status).containsExactly(BindingStatus.READY);
        assertThat(service.register(user, DEMO, VALUES).undeclaredTools()).isZero();
    }

    @Test
    @DisplayName("관리자 목록은 같은 그룹 사용자의 바인딩마다 에이전트와 재시작 대기 시각을 준다")
    void adminListShowsSameGroupBindings() {
        CurrentUser member = user(UserRole.MEMBER, 1L);
        CurrentUser outsider = user(UserRole.MEMBER, 2L);
        CurrentUser admin = user(UserRole.ADMIN, 1L);
        service.register(member, DEMO, VALUES);
        service.register(outsider, DEMO, VALUES);
        Agent agent = agent(member, false);
        ConnectorBinding binding = ConnectorBinding.pending(agent, stored(member), "demo", NOW);
        binding.installed(true, NOW);
        bindings.save(binding);
        readyBinding(agent(outsider, false), stored(outsider), "demo");

        List<AdminConnectionSnapshot> listed = service.listForAdmin(admin);

        assertThat(listed)
                .containsExactly(new AdminConnectionSnapshot(
                        DEMO, member.id(), member.displayName(), agent.code(), BindingStatus.PENDING, true, NOW, 0));
        assertCode(() -> service.listForAdmin(member), ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("호출 제한을 넘은 선택지 조회는 도구를 부르지 않고 거절되며 해제는 제한과 관계없이 된다")
    void rejectsOptionsOverLimitWithoutCallingToolAndStillDisconnects() {
        CurrentUser user = user(UserRole.MEMBER, 1L);
        service.register(user, DEMO, VALUES);
        ConnectorConnectionService limited = serviceLimitedTo(1, 1);

        assertThat(limited.options(user, DEMO, "scope", VALUES)).hasSize(2);
        assertCode(() -> limited.options(user, DEMO, "scope", VALUES), ErrorCode.CONNECTOR_RATE_LIMITED);
        assertCode(() -> limited.register(user, DEMO, VALUES), ErrorCode.CONNECTOR_RATE_LIMITED);
        assertCode(() -> limited.check(user, DEMO), ErrorCode.CONNECTOR_RATE_LIMITED);

        // 등록의 확인 한 번과 받아들인 선택지 조회 한 번이다.
        verify(connector, times(2)).call(DEMO, "list_scopes", VALUES);
        verify(connector, never()).callWithVault(anyString(), anyString(), anyString());
        // 직접 만든 서비스는 프록시를 거치지 않아 해제의 트랜잭션을 여기서 연다.
        ConnectionSnapshot disconnected =
                new TransactionTemplate(transactionManager).execute(status -> limited.disconnect(user, DEMO));
        assertThat(disconnected.status()).isEqualTo(ConnectionStatus.DISCONNECTED);
    }

    @Test
    @DisplayName("한 사용자가 호출 제한에 걸려도 다른 사용자의 선택지 조회는 된다")
    void limitsEachUserSeparately() {
        CurrentUser first = user(UserRole.MEMBER, 1L);
        CurrentUser second = user(UserRole.MEMBER, 1L);
        ConnectorConnectionService limited = serviceLimitedTo(1, 1);
        limited.options(first, DEMO, "scope", VALUES);

        assertCode(() -> limited.options(first, DEMO, "scope", VALUES), ErrorCode.CONNECTOR_RATE_LIMITED);
        assertThat(limited.options(second, DEMO, "scope", VALUES)).hasSize(2);
    }

    @Test
    @DisplayName("manifest 로 열 수 없는 toolset 을 선언한 커넥터는 없는 커넥터다")
    void manifestDeclaringClosedToolsetsIsLeftOut() {
        for (List<String> declared : List.of(List.of("terminal"), List.of("vision", "file"), List.of("sight"))) {
            when(connector.readCatalog())
                    .thenReturn(List.of(
                            new ConnectorManifest(
                                    DEMO,
                                    "검사용 메모",
                                    "",
                                    DEMO_MANIFEST.fields(),
                                    "list_scopes",
                                    "demo",
                                    declared,
                                    false,
                                    1,
                                    List.of()),
                            PIN_MANIFEST));
            CurrentUser user = user(UserRole.MEMBER, 1L);

            assertThat(service.catalog(user)).extracting(ConnectorSummary::id).containsExactly(PIN);
            assertCode(() -> service.register(user, DEMO, VALUES), ErrorCode.CONNECTOR_NOT_FOUND);
        }
        verify(toolsets, never()).writeApiServer(anyString(), anyList(), anyString());
    }

    @Test
    @DisplayName("vision 없이 사진 받기를 선언한 커넥터는 없는 커넥터다")
    void manifestAcceptingAttachmentsWithoutVisionIsLeftOut() {
        when(connector.readCatalog())
                .thenReturn(List.of(new ConnectorManifest(
                        DEMO,
                        "검사용 메모",
                        "",
                        DEMO_MANIFEST.fields(),
                        "list_scopes",
                        "demo",
                        List.of(),
                        true,
                        1,
                        List.of())));
        CurrentUser user = user(UserRole.MEMBER, 1L);

        assertCode(() -> service.register(user, DEMO, VALUES), ErrorCode.CONNECTOR_NOT_FOUND);
    }

    @Test
    @DisplayName("위험도의 하한보다 느슨하게 선언한 커넥터는 카탈로그에 나오지 않는 없는 커넥터다")
    void manifestDeclaringApprovalBelowFloorIsLeftOut() {
        when(connector.readCatalog())
                .thenReturn(List.of(
                        policyManifest(List.of(
                                new ConnectorTool("list_scopes", "READ", "none", null, null),
                                new ConnectorTool("write_note", "WRITE", "none", null, null))),
                        PIN_MANIFEST));
        CurrentUser user = user(UserRole.MEMBER, 1L);

        assertThat(service.catalog(user)).extracting(ConnectorSummary::id).containsExactly(PIN);
        assertCode(() -> service.register(user, DEMO, VALUES), ErrorCode.CONNECTOR_NOT_FOUND);
    }

    @Test
    @DisplayName("판이나 도구 선언을 읽을 수 없던 커넥터만 카탈로그에서 빠지고 다른 커넥터는 남는다")
    void manifestWithUnreadableSchemaOrToolsIsLeftOutAlone() {
        // 클라이언트는 판이 정수가 아니거나 도구 선언이 객체가 아닌 커넥터를 판 0 과 빈 도구로 읽는다.
        ConnectorManifest unreadable = new ConnectorManifest(
                DEMO,
                DEMO_MANIFEST.title(),
                DEMO_MANIFEST.description(),
                DEMO_MANIFEST.fields(),
                "list_scopes",
                "demo",
                List.of(),
                false,
                0,
                List.of());
        when(connector.readCatalog()).thenReturn(List.of(unreadable, PIN_MANIFEST));
        CurrentUser user = user(UserRole.MEMBER, 1L);

        assertThat(service.catalog(user)).extracting(ConnectorSummary::id).containsExactly(PIN);
        assertCode(() -> service.register(user, DEMO, VALUES), ErrorCode.CONNECTOR_NOT_FOUND);
    }

    @Test
    @DisplayName("카탈로그는 선언한 도구의 이름과 제목과 위험도와 승인 방식을 선언한 순서로 준다")
    void catalogListsDeclaredTools() {
        when(connector.readCatalog()).thenReturn(List.of(POLICY_MANIFEST, PIN_MANIFEST));
        CurrentUser user = user(UserRole.MEMBER, 1L);

        List<ConnectorSummary> listed = service.catalog(user);

        assertThat(listed.get(0).tools())
                .containsExactly(
                        new ConnectorToolSummary("list_scopes", null, ToolRisk.READ, ToolApproval.NONE, false),
                        new ConnectorToolSummary("write_note", "메모 쓰기", ToolRisk.WRITE, ToolApproval.REQUIRED, true),
                        new ConnectorToolSummary(
                                "purge_notes", null, ToolRisk.DESTRUCTIVE, ToolApproval.ALWAYS, false));
        // 도구를 선언하지 않는 판의 커넥터는 빈 목록이다.
        assertThat(listed.get(1).tools()).isEmpty();
    }

    @Test
    @DisplayName("카탈로그의 도구는 상시 허락을 줄 수 있는지를 함께 주고 required 도구만 참이 될 수 있다")
    void catalogListsWhetherEachToolCanBeGranted() {
        when(connector.readCatalog())
                .thenReturn(List.of(policyManifest(List.of(
                        new ConnectorTool("list_scopes", "READ", "none", null, Boolean.TRUE),
                        new ConnectorTool("write_note", "WRITE", "required", null, null),
                        new ConnectorTool("share_note", "WRITE", "required", null, Boolean.TRUE),
                        new ConnectorTool("mail_note", "WRITE", "required", null, Boolean.FALSE)))));

        List<ConnectorSummary> listed = service.catalog(user(UserRole.MEMBER, 1L));

        assertThat(listed.get(0).tools())
                .extracting(ConnectorToolSummary::name, ConnectorToolSummary::grant)
                .containsExactly(
                        tuple("list_scopes", false),
                        tuple("write_note", true),
                        tuple("share_note", true),
                        tuple("mail_note", false));
    }

    private static ConnectorManifest policyManifest(List<ConnectorTool> tools) {
        return new ConnectorManifest(
                DEMO,
                DEMO_MANIFEST.title(),
                DEMO_MANIFEST.description(),
                DEMO_MANIFEST.fields(),
                "list_scopes",
                "demo",
                List.of(),
                false,
                2,
                tools);
    }

    /** 주어진 한도의 limiter 를 쓰는 서비스를 직접 만든다. 주입된 서비스는 검사 설정의 넉넉한 한도를 쓴다. */
    private ConnectorConnectionService serviceLimitedTo(int maxConcurrentCalls, int callsPerMinute) {
        return new ConnectorConnectionService(
                connections,
                bindings,
                users,
                bindingService,
                connector,
                transactionManager,
                new ConnectorCallLimiter(
                        new ConnectorProperties(maxConcurrentCalls, callsPerMinute),
                        Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)),
                approvals,
                Clock.systemUTC());
    }

    private void assertVerifyFailure(
            CurrentUser user, Map<String, String> values, ConnectorCallError error, ErrorCode expected) {
        when(connector.call(anyString(), anyString(), anyMap())).thenReturn(CallResult.failure(error));
        assertCode(() -> service.register(user, DEMO, values), expected);
    }

    private static void assertCode(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.code()).isEqualTo(expected));
    }

    private static ConnectorState state(String mode) {
        return new ConnectorState("p", true, true, false, true, mode);
    }

    /** 반영이 끝난 바인딩을 넣는다. 옛 커넥터 에이전트의 바인딩은 그 에이전트를 켠다. */
    private ConnectorBinding readyBinding(Agent agent, ConnectorConnection connection, String server) {
        ConnectorBinding binding = ConnectorBinding.pending(agent, connection, server, NOW);
        binding.installed(false, NOW);
        binding.ready(NOW);
        agents.save(agent);
        return bindings.save(binding);
    }

    /** 설치는 끝났고 재시작 대기 없이 반영 확인만 남은 바인딩을 넣는다. */
    private ConnectorBinding pendingBinding(Agent agent, ConnectorConnection connection) {
        ConnectorBinding binding = ConnectorBinding.pending(agent, connection, "demo", NOW);
        binding.installed(false, NOW);
        return bindings.save(binding);
    }

    /** 이 변경 전에 만든 연결처럼 보관 파일이 없는 READY 연결을 넣는다. 옛 커넥터 에이전트와는 바인딩으로 잇는다. */
    private ConnectorConnection legacyConnection(CurrentUser owner) {
        ConnectorConnection connection = ConnectorConnection.pending(owner.id(), DEMO, NOW);
        connection.ready(NOW);
        return connections.save(connection);
    }

    private Agent agent(CurrentUser owner, boolean connectorManaged) {
        String code = "conn-" + UUID.randomUUID().toString().substring(0, 13);
        Agent agent = Agent.of(
                code,
                code,
                "profile-" + code,
                "http://agent-runtime.test/p/" + code,
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE,
                owner.id(),
                NOW);
        if (connectorManaged) {
            agent.markConnectorManaged();
        }
        return agents.save(agent);
    }

    private ConnectorConnection stored(CurrentUser user) {
        return connections.findByUserIdAndConnectorId(user.id(), DEMO).orElseThrow();
    }

    private String fieldsColumn(CurrentUser user, String connectorId) {
        return jdbc.queryForObject(
                "SELECT fields FROM connector_connection WHERE user_id = ? AND connector_id = ?",
                String.class,
                user.id(),
                connectorId);
    }

    private Boolean agentEnabled(Agent agent) {
        return jdbc.queryForObject("SELECT enabled FROM agent WHERE id = ?", Boolean.class, agent.id());
    }

    private CurrentUser user(UserRole role, long groupId) {
        String suffix = UUID.randomUUID().toString();
        AppUser saved =
                users.save(AppUser.of("connector-" + suffix + "@example.com", suffix, groupId, role, Instant.now()));
        return new CurrentUser(saved.id(), saved.email(), saved.displayName(), saved.groupId(), saved.role());
    }
}
