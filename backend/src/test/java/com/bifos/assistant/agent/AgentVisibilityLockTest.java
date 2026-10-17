package com.bifos.assistant.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.application.AgentLifecycleService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.connector.connection.application.ConnectorBindingService;
import com.bifos.assistant.connector.connection.application.dto.AgentConnectionView;
import com.bifos.assistant.connector.connection.domain.ConnectionFields;
import com.bifos.assistant.connector.connection.domain.ConnectorConnection;
import com.bifos.assistant.connector.connection.infra.ConnectorBindingRepository;
import com.bifos.assistant.connector.connection.infra.ConnectorConnectionRepository;
import com.bifos.assistant.hermes.application.port.HermesConnectorClient;
import com.bifos.assistant.hermes.application.port.HermesConnectorClient.InstallResult;
import com.bifos.assistant.hermes.application.port.HermesDashboardClient;
import com.bifos.assistant.hermes.infra.HermesProfileKeyStore;
import com.bifos.assistant.hermes.application.port.HermesToolsetClient;
import com.bifos.assistant.hermes.application.dto.ConnectorManifest;
import com.bifos.assistant.hermes.application.dto.ConnectorTool;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 붙이기와 공개 범위 변경이 같은 에이전트에 함께 올 때 에이전트 행 잠금으로 줄을 서는지 본다(ADR-083).
 *
 * <p>{@link AgentVisibilityMysqlLockTest} 가 같은 검사를 MySQL 에서 돌린다. 테스트 클래스에 트랜잭션을 두지 않는다. 서비스의
 * 트랜잭션과 행 잠금이 실제로 돌아야 동시 요청의 차례를 볼 수 있다. Hermes 대시보드와 커넥터, 도구 목록 경로만 대역이다.
 */
@BackendIntegrationTest
class AgentVisibilityLockTest {

    /** plugin 틀이 붙이는 안전한 기본 도구다. 셸과 파일 등급이 없다. */
    private static final List<String> SAFE_TOOLSETS = List.of("web", "skills", "todo");

    private static final String DEMO = "demo-notes";

    /** MCP 서버 이름이 {@code demo} 인 커넥터다. */
    private static final ConnectorManifest DEMO_MANIFEST = new ConnectorManifest(
        DEMO,
        "검사용 메모",
        "",
        List.of(),
        "list_scopes",
        "demo",
        2,
        List.of(new ConnectorTool("list_scopes", "READ", "none", null, null)));

    @Autowired
    AgentLifecycleService lifecycle;

    @Autowired
    AgentRepository agents;

    @Autowired
    AppUserRepository users;

    @Autowired
    HermesProfileKeyStore keyStore;

    @Autowired
    ConnectorBindingService bindingService;

    @Autowired
    ConnectorBindingRepository bindingRows;

    @Autowired
    ConnectorConnectionRepository connections;

    @Autowired
    HermesConnectorClient connector;

    @Autowired
    HermesDashboardClient dashboard;

    @Autowired
    HermesToolsetClient toolsets;

    /** 이 테스트가 실제 key 디렉터리에 남긴 파일을 지우려고 적어 둔다. */
    private final List<String> createdProfiles = new ArrayList<>();


    @BeforeEach
    void setUp() {
        when(toolsets.readEnabled(anyString(), anyString())).thenReturn(SAFE_TOOLSETS);
    }


    @AfterEach
    void tearDown() {
        createdProfiles.forEach(keyStore::delete);
        // 바인딩 줄이 에이전트를 가리켜 남으면 같은 컨텍스트의 다른 검사가 에이전트를 지우지 못한다.
        bindingRows.deleteAll();
        connections.deleteAll();
    }


    /**
     * 설치 HTTP가 멈춰도 공개 범위 변경은 시작 트랜잭션이 커밋한 바인딩을 보고 거절된다.
     *
     * <p>붙이기가 대시보드에 설치를 보내는 자리에서 멈춘다. 그때 사용자와 에이전트 잠금은 풀렸지만 바인딩은 이미 저장됐다.
     */
    @Test
    @DisplayName("설치 응답을 기다리는 동안에도 공개 범위 변경은 커밋된 바인딩을 보고 바로 거절된다")
    void visibilityChangeSeesCommittedBindingWhileInstallWaits() throws Exception {
        CurrentUser kid = member();
        Agent created = create(kid, "숙제 도우미", null);
        readyConnection(kid);
        when(connector.readCatalog()).thenReturn(List.of(DEMO_MANIFEST));
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(connector.bindConnector(anyString(), anyString(), anyString(), anyString(), nullable(String.class)))
            .thenAnswer(invocation -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                locked.countDown();
                assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
                return new InstallResult(true, false);
            });
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<AgentConnectionView> binding = pool.submit(() -> bindingService.bind(kid, created.code(), DEMO));
            assertThat(locked.await(10, TimeUnit.SECONDS)).as("붙이기가 설치 응답을 기다린다").isTrue();
            Future<Agent> changing = pool.submit(() -> lifecycle.changeVisibility(kid, created.code(), AgentVisibility.GROUP));
            assertThatThrownBy(() -> changing.get(10, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .cause()
                .isInstanceOfSatisfying(
                    ApiException.class,
                    ex -> assertThat(ex.code()).isEqualTo(ErrorCode.AGENT_CONNECTIONS_REQUIRE_PRIVATE));
            release.countDown();
            assertThat(binding.get(10, TimeUnit.SECONDS).bound()).isTrue();
            assertThat(agents.findByCode(created.code()).orElseThrow().visibility())
                .isEqualTo(AgentVisibility.PRIVATE);
            assertThat(bindingRows.existsByAgentId(created.id())).isTrue();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }


    private Agent create(CurrentUser user, String name, AgentVisibility visibility) {
        Agent created = lifecycle.create(user, name, visibility);
        createdProfiles.add(created.hermesProfile());
        return created;
    }


    /** 값을 보관 파일에 둔 그 사용자의 READY 연결이다. */
    private ConnectorConnection readyConnection(CurrentUser owner) {
        ConnectorConnection connection = ConnectorConnection.pending(owner.id(), DEMO, Instant.now());
        connection.connected(ConnectionFields.empty(), Instant.now());
        return connections.save(connection);
    }


    private CurrentUser member() {
        String email = "visibility-lock-" + UUID.randomUUID() + "@example.com";
        AppUser saved = users.save(AppUser.of(email, email, 1L, UserRole.MEMBER));
        return new CurrentUser(saved.id(), email, email, 1L, UserRole.MEMBER);
    }
}
