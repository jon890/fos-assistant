package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.application.ChatService;
import com.bifos.assistant.chat.application.TurnCancellation;
import com.bifos.assistant.chat.application.model.AutoTurnResult;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.domain.ResultDelivery;
import com.bifos.assistant.chat.domain.type.DeliveryStatus;
import com.bifos.assistant.chat.infra.ChatAttachmentRepository;
import com.bifos.assistant.chat.infra.ChatMessageRepository;
import com.bifos.assistant.chat.infra.ChatPendingMessageRepository;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.chat.infra.ResultDeliveryAttemptRepository;
import com.bifos.assistant.chat.infra.ResultDeliveryItemRepository;
import com.bifos.assistant.chat.infra.ResultDeliveryRepository;
import com.bifos.assistant.connector.application.ConnectorActionResultSource;
import com.bifos.assistant.connector.application.ConnectorActionService;
import com.bifos.assistant.connector.application.ConnectorCatalogCache;
import com.bifos.assistant.connector.domain.ConnectorConnection;
import com.bifos.assistant.connector.infra.ConnectorConnectionRepository;
import com.bifos.assistant.hermes.ConnectorExecutionUnknown;
import com.bifos.assistant.hermes.HermesConnectorClient;
import com.bifos.assistant.hermes.HermesRunEventStream;
import com.bifos.assistant.hermes.HermesRunsClient;
import com.bifos.assistant.hermes.StubHermesRunsClient;
import com.bifos.assistant.hermes.dto.CallResult;
import com.bifos.assistant.hermes.dto.ConnectorManifest;
import com.bifos.assistant.hermes.dto.ConnectorTool;
import com.bifos.assistant.hermes.dto.HermesRunCommand;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.hermes.dto.TokenUsage;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.infra.ExecutionEventRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

/**
 * 승인해 실행한 결과의 전달을 다시 전달하면 승인 줄에 저장된 결과만 다시 읽고 커넥터를 다시 부르지 않는지 본다(ADR-050,
 * ADR-075).
 *
 * <p>구성은 {@link ConnectorActionDeliveryTest} 와 같게 둔다. 같은 Spring 컨텍스트를 써서 컨텍스트 수를 늘리지 않는다. 승인은
 * 실제 승인 경로로 하고, 커넥터 실행은 대역이 답한다.
 *
 * <p>카탈로그 캐시는 그 컨텍스트가 함께 쓴다. 이 검사의 커넥터는 다른 검사가 쓰지 않는 번호라, 여기서 읽은 카탈로그가 남아도
 * 다른 검사에는 그 커넥터의 manifest 가 없는 것으로 보여 카탈로그를 읽지 못했을 때와 같다. 반대로 다른 검사가 남긴 읽기 실패는
 * 잠시 기억되므로 준비에서 그 기억이 지나기를 기다린다.
 */
@SpringBootTest(properties = "assistant.delegation-wake.enabled=true")
@ActiveProfiles("test")
@Import(ChatServiceTest.StubRuntime.class)
class ConnectorDeliveryRetryTest {

    private static final Duration WAIT_LIMIT = Duration.ofSeconds(10);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String CONNECTOR = "retry-notes";
    private static final String WRITE = "write_note";

    /** MCP 서버 이름이 {@code demo} 라 등록 이름은 {@code mcp__demo__write_note} 다. 승인을 받는 쓰기 도구 하나를 선언한다. */
    private static final ConnectorManifest MANIFEST = new ConnectorManifest(
            CONNECTOR,
            "다시 전달 검사용 메모",
            "",
            List.of(),
            "list_scopes",
            "demo",
            List.of(),
            false,
            2,
            List.of(
                    new ConnectorTool("list_scopes", "READ", "none", null, null),
                    new ConnectorTool(WRITE, "WRITE", "required", "메모 쓰기", null)));

    /** {@code ConnectorActionResultSource} 의 입력 글이다. 상수는 package-private 이라 글로 견준다. */
    private static final String UNKNOWN_INPUT = "실행 여부를 알 수 없다. 다시 실행하지 말고 사용자에게 확인을 부탁한다.";

    @MockitoBean
    HermesRunEventStream eventStream;

    @MockitoBean
    HermesConnectorClient connector;

    @Autowired
    ChatService chat;

    @Autowired
    ConnectorActionService actionService;

    @Autowired
    ConnectorActionResultSource resultSource;

    @Autowired
    ConnectorCatalogCache catalog;

    @Autowired
    TurnCancellation turns;

    @Autowired
    AppUserRepository users;

    @Autowired
    AgentRepository agents;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    ChatMessageRepository messages;

    @Autowired
    ChatAttachmentRepository attachmentRows;

    @Autowired
    ChatPendingMessageRepository pendingRows;

    @Autowired
    AgentExecutionRepository executions;

    @Autowired
    ExecutionEventRepository executionEvents;

    @Autowired
    MemoryRepository memories;

    @Autowired
    ConnectorConnectionRepository connections;

    @Autowired
    ResultDeliveryRepository deliveries;

    @Autowired
    ResultDeliveryItemRepository deliveryItems;

    @Autowired
    ResultDeliveryAttemptRepository attempts;

    @Autowired
    HermesRunsClient hermes;

    @Autowired
    JdbcTemplate jdbc;

    private CurrentUser dad;
    private Agent chief;
    private Conversation conversation;
    private AgentExecution root;

    private StubHermesRunsClient stub() {
        return (StubHermesRunsClient) hermes;
    }

    @BeforeEach
    void setUp() {
        awaitAllIdle();
        stub().reset();
        jdbc.update("DELETE FROM connector_action");
        connections.deleteAll();
        deliveryItems.deleteAll();
        attempts.deleteAll();
        deliveries.deleteAll();
        pendingRows.deleteAll();
        executionEvents.deleteAll();
        executions.deleteAll();
        attachmentRows.deleteAll();
        messages.deleteAll();
        agents.deleteAll();
        memories.deleteAll();
        users.deleteAll();
        when(connector.readCatalog()).thenReturn(List.of(MANIFEST));
        when(connector.execute(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(CallResult.success(JSON.readTree("{\"saved\":true}")));
        awaitCatalogReadable();

        AppUser user = users.save(AppUser.of("dad@example.com", "dad", 1L, UserRole.MEMBER, Instant.now()));
        dad = new CurrentUser(user.id(), user.email(), user.displayName(), user.groupId(), user.role());
        chief = agents.save(agent("dad", "비서", user.id()));
        Agent notes = agent("retry-notes-agent", "메모 연결", user.id());
        notes.markConnectorManaged();
        notes = agents.save(notes);
        ConnectorConnection connection = ConnectorConnection.pending(user.id(), CONNECTOR, notes, Instant.now());
        connection.ready(Instant.now());
        connections.save(connection);
        conversation = conversations.save(Conversation.startedBy(dad.id(), "대화", chief.id(), Instant.now()));
        root = executions.save(AgentExecution.builder()
                .userId(dad.id())
                .conversationId(conversation.id())
                .agentId(chief.id())
                .profileName("dad")
                .costMode(CostMode.SUBSCRIPTION)
                .status(ExecutionStatus.SUCCEEDED)
                .startedAt(Instant.now())
                .build());
    }

    @AfterEach
    void tearDown() {
        awaitAllIdle();
        jdbc.update("DELETE FROM connector_action");
        // 연결 줄이 에이전트를 가리켜 남겨 두면 같은 컨텍스트의 다른 검사가 에이전트를 지우지 못한다.
        connections.deleteAll();
    }

    @Test
    @DisplayName("승인해 실행한 결과의 자동 turn 이 실패한 뒤 다시 전달하면 저장된 결과로 같은 입력을 보내고 커넥터는 다시 부르지 않는다")
    void retriesApprovedResultWithoutExecutingAgain() {
        ResultDelivery delivery = failedDeliveryOf(approved());
        verify(connector, times(1)).execute(anyString(), anyString(), anyString(), anyString());

        chat.retryDelivery(dad, conversation.id(), delivery.id(), event -> {});

        verify(connector, times(1)).execute(anyString(), anyString(), anyString(), anyString());
        List<HermesRunCommand> received = stub().received();
        assertThat(received).hasSize(2);
        assertThat(received.get(1).input())
                .contains("승인한 동작의 결과가 도착했다.")
                .contains("<external-data>\n{\"saved\":true}\n</external-data>")
                .isEqualTo(received.get(0).input());
        assertThat(deliveries.findById(delivery.id()).orElseThrow().status()).isEqualTo(DeliveryStatus.DELIVERED);
    }

    @Test
    @DisplayName("결과를 모르는 UNKNOWN 승인 결과를 다시 전달하면 다시 실행하지 말라는 입력을 보내고 커넥터는 한 번만 불린다")
    void retriesUnknownResultWithDoNotRunAgainInput() {
        when(connector.execute(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new ConnectorExecutionUnknown());
        ResultDelivery delivery = failedDeliveryOf(approved());

        chat.retryDelivery(dad, conversation.id(), delivery.id(), event -> {});

        verify(connector, times(1)).execute(anyString(), anyString(), anyString(), anyString());
        List<HermesRunCommand> received = stub().received();
        assertThat(received).hasSize(2);
        assertThat(received.get(1).input())
                .contains("[출처: 승인한 동작, 동작: 메모 쓰기, 상태: UNKNOWN, 끝난 시각: ")
                .contains("]\n" + UNKNOWN_INPUT);
        assertThat(deliveries.findById(delivery.id()).orElseThrow().status()).isEqualTo(DeliveryStatus.DELIVERED);
    }

    @Test
    @DisplayName("다른 사용자의 승인 줄 열쇠와 UUID 가 아닌 열쇠는 다시 읽지 않는다")
    void readsOnlyOwnActionsByKey() {
        AppUser mom = users.save(AppUser.of("mom@example.com", "mom", 1L, UserRole.MEMBER, Instant.now()));
        UUID momsAction = insertAction(mom.id(), "SUCCEEDED", "{\"saved\":true}");
        UUID dadsAction = insertAction(dad.id(), "SUCCEEDED", "{\"saved\":true}");

        assertThat(resultSource.resultsFor(conversation.id(), dad.id(), List.of(momsAction.toString())))
                .as("다른 사용자의 승인 줄")
                .isEmpty();
        assertThat(resultSource.resultsFor(conversation.id(), dad.id(), List.of("not-a-uuid", dadsAction.toString())))
                .extracting(AutoTurnResult::key)
                .containsExactly(dadsAction.toString());
    }

    /** 승인 줄을 하나 만들고 실제 승인 경로로 승인한다. 대역 커넥터가 실행에 답한다. */
    private UUID approved() {
        UUID actionId = insertAction(dad.id(), "PENDING", null);
        stub().willReturn(result("auto", "failed", null));
        actionService.approve(dad, actionId, null);
        return actionId;
    }

    /** 승인 결과의 자동 turn 이 provider 실패로 끝나기를 기다려 그 묶음을 돌려준다. 다시 전달 때는 완료로 답한다. */
    private ResultDelivery failedDeliveryOf(UUID actionId) {
        awaitReceived(1);
        awaitIdle(conversation.id());
        List<ResultDelivery> rows = deliveries.findAll();
        assertThat(rows).as("승인 결과 %s 의 전달 묶음", actionId).hasSize(1);
        assertThat(rows.getFirst().status()).as("첫 시도가 실패한 묶음").isEqualTo(DeliveryStatus.FAILED);
        stub().willReturn(result("retry", "completed", "다시 정리한 답"));
        return rows.getFirst();
    }

    /** 이 검사의 커넥터로 승인 줄 하나를 그 상태로 넣는다. 승인 줄을 만드는 길은 다른 검사가 본다. */
    private UUID insertAction(Long userId, String status, String resultText) {
        UUID actionId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO connector_action (public_id, user_id, agent_id, connector_id, tool_name, hermes_tool, risk,
                    approval_mode, decision, passed, status, origin_execution_id, conversation_id, dedupe_key,
                    args_json, args_sha256, expires_at, result_text, error_code, created_at)
                VALUES (?, ?, ?, ?, ?, 'mcp__demo__write_note', 'WRITE', 'REQUIRED', 'NEEDS_APPROVAL',
                    FALSE, ?, ?, ?, ?, '{"text":"안녕"}', 'sha', ?, ?, NULL, CURRENT_TIMESTAMP(6))
                """,
                bytes(actionId),
                userId,
                chief.id(),
                CONNECTOR,
                WRITE,
                status,
                root.id(),
                conversation.id(),
                UUID.randomUUID().toString(),
                Timestamp.from(Instant.now().plus(Duration.ofHours(24))),
                resultText);
        return actionId;
    }

    private static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16)
                .putLong(id.getMostSignificantBits())
                .putLong(id.getLeastSignificantBits())
                .array();
    }

    private static Agent agent(String code, String name, Long ownerId) {
        return Agent.of(
                code,
                name,
                code,
                "http://agent-runtime.test/p/" + code,
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE,
                ownerId,
                Instant.now());
    }

    private static HermesRunResult result(String runId, String status, String output) {
        return HermesRunResult.of(runId, "session", status, output, "model", "provider", TokenUsage.empty());
    }

    /**
     * 카탈로그 캐시가 이 검사의 manifest 를 낼 때까지 기다린다.
     *
     * <p>같은 컨텍스트의 다른 검사는 카탈로그 읽기를 실패시킨다. 캐시는 그 실패를 실제 시계로 잠시 기억하므로, 그 사이에는 이
     * 검사의 대역 답을 읽지 않는다.
     */
    private void awaitCatalogReadable() {
        long deadline = System.nanoTime() + WAIT_LIMIT.toNanos();
        while (true) {
            try {
                if (catalog.find(CONNECTOR).isPresent()) {
                    return;
                }
            } catch (RuntimeException ex) {
                // 앞 검사가 남긴 읽기 실패를 기억하는 동안이다.
            }
            if (System.nanoTime() > deadline) {
                fail("카탈로그 캐시가 %s 안에 %s 의 manifest 를 내지 않았다", WAIT_LIMIT, CONNECTOR);
            }
            pause(100);
        }
    }

    private void awaitReceived(int count) {
        long deadline = System.nanoTime() + WAIT_LIMIT.toNanos();
        while (stub().received().size() < count) {
            if (System.nanoTime() > deadline) {
                fail(
                        "Hermes 가 %d 번 받지 못했다 received=%d",
                        count, stub().received().size());
            }
            pause(10);
        }
    }

    private void awaitAllIdle() {
        conversations.findAll().forEach(it -> awaitIdle(it.id()));
    }

    private void awaitIdle(Long conversationId) {
        long deadline = System.nanoTime() + WAIT_LIMIT.toNanos();
        while (turns.markOf(conversationId).running()) {
            if (System.nanoTime() > deadline) {
                fail("대화 %d 의 turn 이 %s 안에 끝나지 않았다", conversationId, WAIT_LIMIT);
            }
            pause(10);
        }
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            fail("기다리는 중에 끊겼다");
        }
    }
}
