package com.bifos.assistant.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentVisibility;
import com.bifos.assistant.agent.domain.CostMode;
import com.bifos.assistant.agent.domain.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.connector.application.ConnectorCatalogCache;
import com.bifos.assistant.connector.application.ConnectorPolicyProperties;
import com.bifos.assistant.connector.domain.ConnectorConnection;
import com.bifos.assistant.connector.infra.ConnectorConnectionRepository;
import com.bifos.assistant.hermes.HermesConnectorClient;
import com.bifos.assistant.hermes.dto.ConnectorManifest;
import com.bifos.assistant.hermes.dto.ConnectorTool;
import com.bifos.assistant.mcp.McpCallSigner;
import com.bifos.assistant.mcp.application.AgentTokenService;
import com.bifos.assistant.mcp.infra.AgentTokenRepository;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.domain.UserRole;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 실제 HTTP 경계에서 커넥터 도구 호출 판정 경로의 인증과 판정과 기록을 확인한다(ADR-048).
 *
 * <p>계약은 {@code docs/connectors.md} 의 「도구 호출 판정」 이다. 본문 서명은 운영 코드가 아니라 {@link McpCallSigner}
 * 가 따로 계산한다. 카탈로그는 대역이 내고, 보관 시간에 걸리지 않게 검사마다 시계를 보관 시간보다 멀리 옮긴다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ConnectorPolicyEndpointTest {
    private static final String PATH = "/internal/hermes/connector-policy";
    private static final String PROFILE = "connector-policy-owner";
    private static final String OTHER_PROFILE = "connector-policy-other";
    private static final String DEMO = "demo-notes";
    private static final String ARGS = "{\"text\":\"안녕\"}";
    private static final Duration TTL = Duration.ofSeconds(60);
    private static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");
    private static final MovingClock CLOCK = new MovingClock(NOW);
    private static final String NEEDS_APPROVAL_MESSAGE =
            "이 작업은 사용자 승인이 필요해 아직 실행하지 않았다. 같은 호출을 다시 시도하지 말고 사용자에게 승인이 필요하다고 알린다.";

    /** 도구마다 정책을 선언한 커넥터다. MCP 서버 이름이 {@code demo} 라 등록 이름은 {@code mcp__demo__<도구>} 다. */
    private static final ConnectorManifest DECLARING = manifest(
            2,
            List.of(
                    new ConnectorTool("list_scopes", "READ", "none", null),
                    new ConnectorTool("write_note", "WRITE", "required", "메모 쓰기"),
                    new ConnectorTool("share_note", "SENSITIVE", "required", null),
                    new ConnectorTool("purge_notes", "DESTRUCTIVE", "always", null),
                    new ConnectorTool("pay_invoice", "FINANCIAL", "always", null)));

    @TestConfiguration
    static class MovingCatalogClock {
        /** 운영의 캐시 대신 검사가 시각을 옮길 수 있는 캐시를 끼운다. */
        @Bean
        @Primary
        ConnectorCatalogCache movingCatalogCache(HermesConnectorClient connector) {
            return new ConnectorCatalogCache(
                    connector, new ConnectorPolicyProperties(TTL, Duration.ofSeconds(5)), CLOCK);
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    AgentTokenService tokens;

    @Autowired
    AgentTokenRepository tokenRepository;

    @Autowired
    AppUserRepository users;

    @Autowired
    AgentRepository agents;

    @Autowired
    ConnectorConnectionRepository connections;

    @Autowired
    AgentExecutionRepository executions;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    HermesConnectorClient connector;

    private final HttpClient client = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();
    private String token;
    private String root;
    private AppUser owner;
    private Agent agent;
    private AgentExecution run;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM connector_action");
        connections.deleteAll();
        McpCallSigner.clearRuns(jdbc, List.of(PROFILE, OTHER_PROFILE));
        agents.deleteAll();
        tokenRepository.deleteAll();
        users.deleteAll();
        // 앞선 검사가 읽은 카탈로그가 남지 않게 보관 시간보다 멀리 옮긴다.
        CLOCK.advance(TTL.plusSeconds(1));
        when(connector.readCatalog()).thenReturn(List.of(DECLARING));

        owner = users.save(AppUser.of("policy-owner@example.com", "주인", 1L, UserRole.MEMBER));
        agent = Agent.of(
                "policy-" + UUID.randomUUID(),
                "검사용 메모",
                PROFILE,
                "http://localhost",
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE,
                owner.id());
        agent.markConnectorManaged();
        agent = agents.save(agent);
        token = tokens.issue(PROFILE, "owner").rawToken();
        root = "fos-" + UUID.randomUUID();
        run = executions.save(AgentExecution.builder()
                .userId(owner.id())
                .agentId(agent.id())
                .conversationId(7L)
                .profileName(PROFILE)
                .hermesSessionId(root)
                .costMode(CostMode.SUBSCRIPTION)
                .status(ExecutionStatus.RUNNING)
                .startedAt(NOW)
                .build());
    }

    @Test
    @DisplayName("READY 연결의 READ 도구는 allow 이고 인자 원문 없이 해시만 담은 허용 줄 하나를 남긴다")
    void readToolOfReadyConnectionIsAllowedAndRecorded() throws Exception {
        connect(true);

        HttpResponse<String> response = ask("mcp__demo__list_scopes", "list_scopes");

        assertThat(response.statusCode()).as("응답: %s", response.body()).isEqualTo(200);
        assertThat(json.readTree(response.body()))
                .isEqualTo(json.readTree("{\"decision\":\"allow\",\"message\":\"\",\"action_id\":null}"));
        Map<String, Object> row = onlyRow();
        assertThat(row.get("DECISION")).isEqualTo("ALLOWED");
        assertThat(row.get("DENY_REASON")).isNull();
        assertThat(row.get("PASSED")).isEqualTo(true);
        assertThat(row.get("ARGS_JSON")).isNull();
        // 인자 글 {"text":"안녕"} 의 UTF-8 바이트를 SHA-256 한 값이다.
        assertThat(row.get("ARGS_SHA256"))
                .isEqualTo("a931698523cbd6e244139989151aab853d748a592bfc5e3008ecb4cece197a13");
        assertThat(row.get("TOOL_NAME")).isEqualTo("list_scopes");
        assertThat(row.get("HERMES_TOOL")).isEqualTo("mcp__demo__list_scopes");
        assertThat(row.get("RISK")).isEqualTo("READ");
        assertThat(row.get("APPROVAL_MODE")).isEqualTo("NONE");
        assertThat(row.get("CONNECTOR_ID")).isEqualTo(DEMO);
        assertThat(((Number) row.get("USER_ID")).longValue()).isEqualTo(owner.id());
        assertThat(((Number) row.get("AGENT_ID")).longValue()).isEqualTo(agent.id());
        assertThat(((Number) row.get("ORIGIN_EXECUTION_ID")).longValue()).isEqualTo(run.id());
        assertThat(((Number) row.get("CONVERSATION_ID")).longValue()).isEqualTo(7L);
        assertThat(row.get("PUBLIC_ID")).isNotNull();
    }

    @Test
    @DisplayName("WRITE 와 required 인 도구는 block 이고 통과하지 않은 승인 필요 줄을 승인 상태와 인자 원문 없이 남긴다")
    void writeToolIsBlockedAndRecordedAsNeedingApproval() throws Exception {
        connect(true);

        HttpResponse<String> response = ask("mcp__demo__write_note", "write_note");

        assertBlocked(response, NEEDS_APPROVAL_MESSAGE);
        Map<String, Object> row = onlyRow();
        assertThat(row.get("DECISION")).isEqualTo("NEEDS_APPROVAL");
        assertThat(row.get("DENY_REASON")).isNull();
        assertThat(row.get("PASSED")).isEqualTo(false);
        assertThat(row.get("STATUS")).isNull();
        assertThat(row.get("ARGS_JSON")).isNull();
        assertThat(row.get("RISK")).isEqualTo("WRITE");
        assertThat(row.get("APPROVAL_MODE")).isEqualTo("REQUIRED");
        assertThat(row.get("TOOL_NAME")).isEqualTo("write_note");
    }

    @Test
    @DisplayName("SENSITIVE 와 required 인 도구도 block 이고 통과하지 않은 승인 필요 줄을 남긴다")
    void sensitiveToolIsBlockedAndRecordedAsNeedingApproval() throws Exception {
        connect(true);

        HttpResponse<String> response = ask("mcp__demo__share_note", "share_note");

        assertBlocked(response, NEEDS_APPROVAL_MESSAGE);
        Map<String, Object> row = onlyRow();
        assertThat(row.get("DECISION")).isEqualTo("NEEDS_APPROVAL");
        assertThat(row.get("PASSED")).isEqualTo(false);
        assertThat(row.get("RISK")).isEqualTo("SENSITIVE");
        assertThat(row.get("APPROVAL_MODE")).isEqualTo("REQUIRED");
    }

    @Test
    @DisplayName("승인이 필요한 같은 요청을 다시 보내면 같은 block 답이고 줄이 하나다")
    void resendingRequestThatNeedsApprovalReturnsSameBlock() throws Exception {
        connect(true);
        String body = body(token, "mcp__demo__write_note", "write_note", root, newCall(), ARGS);

        HttpResponse<String> first = send(token, body);
        HttpResponse<String> second = send(token, body);

        assertBlocked(first, NEEDS_APPROVAL_MESSAGE);
        assertBlocked(second, NEEDS_APPROVAL_MESSAGE);
        assertThat(json.readTree(second.body())).isEqualTo(json.readTree(first.body()));
        Map<String, Object> row = onlyRow();
        assertThat(row.get("DECISION")).isEqualTo("NEEDS_APPROVAL");
        assertThat(row.get("PASSED")).isEqualTo(false);
    }

    @Test
    @DisplayName("schema 2 에서 tool 이 null 이면 block 이고 등록 이름만 담은 UNDECLARED 줄을 남긴다")
    void nullToolOfDeclaringSchemaIsBlockedAsUndeclared() throws Exception {
        connect(true);

        HttpResponse<String> response = ask("mcp__demo__unknown", null);

        assertBlocked(response, "이 도구는 사용이 허락되지 않아 실행하지 않았다. 다시 부르지 않는다.");
        Map<String, Object> row = onlyRow();
        assertThat(row.get("DECISION")).isEqualTo("DENIED");
        assertThat(row.get("DENY_REASON")).isEqualTo("UNDECLARED");
        assertThat(row.get("PASSED")).isEqualTo(false);
        assertThat(row.get("TOOL_NAME")).isNull();
        assertThat(row.get("HERMES_TOOL")).isEqualTo("mcp__demo__unknown");
        assertThat(row.get("RISK")).isNull();
    }

    @Test
    @DisplayName("tool 이 읽기 도구여도 등록 이름이 다른 도구의 것이면 tool 이 없는 호출로 읽어 UNDECLARED 로 막는다")
    void toolThatDoesNotMatchHermesToolIsNotTrusted() throws Exception {
        connect(true);

        HttpResponse<String> response = ask("mcp__demo__write_note", "list_scopes");

        assertBlocked(response, "이 도구는 사용이 허락되지 않아 실행하지 않았다. 다시 부르지 않는다.");
        Map<String, Object> row = onlyRow();
        assertThat(row.get("DENY_REASON")).isEqualTo("UNDECLARED");
        assertThat(row.get("TOOL_NAME")).isNull();
        assertThat(row.get("HERMES_TOOL")).isEqualTo("mcp__demo__write_note");
    }

    @Test
    @DisplayName("schema 1 에서 선언이 없는 도구는 WRITE 와 required 로 읽어 block 이고 통과하지 않은 승인 필요 줄을 남긴다")
    void undeclaredToolOfLegacySchemaIsReadAsWrite() throws Exception {
        // 도구를 선언하지 않는 판은 대시보드가 부르는 읽기 도구만 담는다.
        when(connector.readCatalog())
                .thenReturn(List.of(manifest(1, List.of(new ConnectorTool("list_scopes", "READ", "none", null)))));
        connect(true);

        HttpResponse<String> unknown = ask("mcp__demo__write_note", null);
        HttpResponse<String> read = ask("mcp__demo__list_scopes", "list_scopes");

        assertBlocked(unknown, NEEDS_APPROVAL_MESSAGE);
        assertThat(json.readTree(read.body()).path("decision").asString()).isEqualTo("allow");
        assertThat(jdbc.queryForList(
                        "SELECT decision, passed, risk, approval_mode, tool_name FROM connector_action ORDER BY id"))
                .extracting(
                        row -> row.get("DECISION"),
                        row -> row.get("PASSED"),
                        row -> row.get("RISK"),
                        row -> row.get("APPROVAL_MODE"),
                        row -> row.get("TOOL_NAME"))
                .containsExactly(
                        tuple("NEEDS_APPROVAL", false, "WRITE", "REQUIRED", null),
                        tuple("ALLOWED", true, "READ", "NONE", "list_scopes"));
    }

    @Test
    @DisplayName("DESTRUCTIVE 도구는 block 이고 RISK_NOT_OPEN 줄을 남긴다")
    void destructiveToolIsBlocked() throws Exception {
        connect(true);

        HttpResponse<String> response = ask("mcp__demo__purge_notes", "purge_notes");

        assertBlocked(response, "이 도구는 아직 열리지 않아 실행하지 않았다. 다시 부르지 않는다.");
        Map<String, Object> row = onlyRow();
        assertThat(row.get("DENY_REASON")).isEqualTo("RISK_NOT_OPEN");
        assertThat(row.get("DECISION")).isEqualTo("DENIED");
        assertThat(row.get("PASSED")).isEqualTo(false);
        assertThat(row.get("RISK")).isEqualTo("DESTRUCTIVE");
        assertThat(row.get("APPROVAL_MODE")).isEqualTo("ALWAYS");
    }

    @Test
    @DisplayName("FINANCIAL 도구는 block 이고 RISK_NOT_OPEN 줄을 남긴다")
    void financialToolIsBlocked() throws Exception {
        connect(true);

        HttpResponse<String> response = ask("mcp__demo__pay_invoice", "pay_invoice");

        assertBlocked(response, "이 도구는 아직 열리지 않아 실행하지 않았다. 다시 부르지 않는다.");
        Map<String, Object> row = onlyRow();
        assertThat(row.get("DECISION")).isEqualTo("DENIED");
        assertThat(row.get("DENY_REASON")).isEqualTo("RISK_NOT_OPEN");
        assertThat(row.get("PASSED")).isEqualTo(false);
        assertThat(row.get("RISK")).isEqualTo("FINANCIAL");
        assertThat(row.get("APPROVAL_MODE")).isEqualTo("ALWAYS");
    }

    @Test
    @DisplayName("인자 글이 16KB 를 넘으면 block 이고 ARGS_TOO_LARGE 줄을 남긴다")
    void argsOverLimitAreBlocked() throws Exception {
        connect(true);
        String args = "{\"text\":\"" + "a".repeat(16 * 1024) + "\"}";

        HttpResponse<String> response =
                send(token, body(token, "mcp__demo__list_scopes", "list_scopes", root, newCall(), args));

        assertBlocked(response, "인자가 너무 커서 실행하지 않았다. 나눠서 요청한다.");
        assertThat(onlyRow().get("DENY_REASON")).isEqualTo("ARGS_TOO_LARGE");
    }

    @Test
    @DisplayName("연결이 PENDING 이면 읽기 도구도 block 이고 NOT_READY 줄을 남긴다")
    void pendingConnectionIsBlocked() throws Exception {
        connect(false);

        HttpResponse<String> response = ask("mcp__demo__list_scopes", "list_scopes");

        assertBlocked(response, "이 연결이 준비되지 않아 실행하지 않았다. 사용자에게 연결 화면에서 연결을 확인하라고 알린다.");
        Map<String, Object> row = onlyRow();
        assertThat(row.get("DENY_REASON")).isEqualTo("NOT_READY");
        assertThat(row.get("RISK")).isNull();
    }

    @Test
    @DisplayName("카탈로그 읽기가 예외이면 block 이고 POLICY_UNAVAILABLE 줄을 남기며 예외 본문을 글에 넣지 않는다")
    void catalogFailureIsBlockedAsPolicyUnavailable() throws Exception {
        connect(true);
        when(connector.readCatalog()).thenThrow(new IllegalStateException("dashboard secret detail"));

        HttpResponse<String> response = ask("mcp__demo__list_scopes", "list_scopes");

        assertBlocked(response, "이 도구의 사용 정책을 지금 확인하지 못해 실행하지 않았다. 잠시 뒤 다시 시도하라고 사용자에게 알린다.");
        assertThat(response.body()).doesNotContain("dashboard secret detail");
        Map<String, Object> row = onlyRow();
        assertThat(row.get("DENY_REASON")).isEqualTo("POLICY_UNAVAILABLE");
        assertThat(row.get("PASSED")).isEqualTo(false);
    }

    @Test
    @DisplayName("카탈로그에 그 커넥터가 없으면 block 이고 POLICY_UNAVAILABLE 줄을 남긴다")
    void connectorMissingFromCatalogIsBlockedAsPolicyUnavailable() throws Exception {
        connect(true);
        when(connector.readCatalog()).thenReturn(List.of());

        HttpResponse<String> response = ask("mcp__demo__list_scopes", "list_scopes");

        assertThat(json.readTree(response.body()).path("decision").asString()).isEqualTo("block");
        assertThat(onlyRow().get("DENY_REASON")).isEqualTo("POLICY_UNAVAILABLE");
    }

    @Test
    @DisplayName("같은 요청을 두 번 보내면 정책이 그 사이 바뀌어도 응답이 같고 줄이 하나다")
    void resendingSameRequestReturnsFirstDecisionAndKeepsOneRow() throws Exception {
        connect(true);
        String body = body(token, "mcp__demo__purge_notes", "purge_notes", root, newCall(), ARGS);
        HttpResponse<String> first = send(token, body);
        // 처음 판정을 그대로 돌려주는지 보려고 같은 도구를 읽기로 바꾼 카탈로그를 다시 읽게 한다.
        when(connector.readCatalog())
                .thenReturn(List.of(manifest(
                        2,
                        List.of(
                                new ConnectorTool("list_scopes", "READ", "none", null),
                                new ConnectorTool("purge_notes", "READ", "none", null)))));
        CLOCK.advance(TTL.plusSeconds(1));

        HttpResponse<String> second = send(token, body);

        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(json.readTree(first.body()).path("decision").asString()).isEqualTo("block");
        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(json.readTree(second.body())).isEqualTo(json.readTree(first.body()));
        assertThat(rows()).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 tool_call_id 로 다른 등록 이름이나 다른 인자를 보내면 앞의 allow 를 돌려주지 않고 block 이며 줄이 하나다")
    void reusedToolCallIdWithAnotherToolOrArgsIsBlockedWithoutNewRow() throws Exception {
        connect(true);
        String call = newCall();
        HttpResponse<String> first =
                send(token, body(token, "mcp__demo__list_scopes", "list_scopes", root, call, ARGS));

        HttpResponse<String> otherTool =
                send(token, body(token, "mcp__demo__write_note", "write_note", root, call, ARGS));
        HttpResponse<String> otherArgs =
                send(token, body(token, "mcp__demo__list_scopes", "list_scopes", root, call, "{\"text\":\"다른 글\"}"));
        HttpResponse<String> same = send(token, body(token, "mcp__demo__list_scopes", "list_scopes", root, call, ARGS));

        assertThat(json.readTree(first.body()).path("decision").asString()).isEqualTo("allow");
        assertBlocked(otherTool, "이 도구 호출의 실행 맥락을 확인하지 못해 실행하지 않았다.");
        assertBlocked(otherArgs, "이 도구 호출의 실행 맥락을 확인하지 못해 실행하지 않았다.");
        // 같은 호출을 다시 보낸 것은 처음 판정을 그대로 받는다.
        assertThat(json.readTree(same.body()).path("decision").asString()).isEqualTo("allow");
        assertThat(onlyRow().get("HERMES_TOOL")).isEqualTo("mcp__demo__list_scopes");
    }

    @Test
    @DisplayName("schema 1 커넥터에서도 다른 MCP 서버의 등록 이름은 block 이고 원래 이름을 비운 UNDECLARED 줄을 남긴다")
    void toolOfAnotherServerIsBlockedAsUndeclaredEvenOnLegacySchema() throws Exception {
        when(connector.readCatalog())
                .thenReturn(List.of(manifest(1, List.of(new ConnectorTool("list_scopes", "READ", "none", null)))));
        connect(true);

        HttpResponse<String> response = ask("mcp__other__x", "x");

        assertBlocked(response, "이 도구는 사용이 허락되지 않아 실행하지 않았다. 다시 부르지 않는다.");
        Map<String, Object> row = onlyRow();
        assertThat(row.get("DECISION")).isEqualTo("DENIED");
        assertThat(row.get("DENY_REASON")).isEqualTo("UNDECLARED");
        assertThat(row.get("TOOL_NAME")).isNull();
        assertThat(row.get("HERMES_TOOL")).isEqualTo("mcp__other__x");
        assertThat(row.get("RISK")).isNull();
    }

    @Test
    @DisplayName("모르는 session 은 block 이고 줄을 남기지 않는다")
    void unknownSessionIsBlockedWithoutRow() throws Exception {
        connect(true);
        String unknown = "fos-" + UUID.randomUUID();

        HttpResponse<String> response =
                send(token, body(token, "mcp__demo__list_scopes", "list_scopes", unknown, newCall(), ARGS));

        assertBlocked(response, "이 도구 호출의 실행 맥락을 확인하지 못해 실행하지 않았다.");
        assertThat(rows()).isZero();
    }

    @Test
    @DisplayName("그 실행의 에이전트에 연결이 없으면 block 이고 줄을 남기지 않는다")
    void runWithoutConnectionIsBlockedWithoutRow() throws Exception {
        HttpResponse<String> response = ask("mcp__demo__list_scopes", "list_scopes");

        assertBlocked(response, "이 도구 호출의 실행 맥락을 확인하지 못해 실행하지 않았다.");
        assertThat(rows()).isZero();
    }

    @Test
    @DisplayName("연결의 주인이 실행의 사용자와 다르면 block 이고 줄을 남기지 않는다")
    void connectionOfAnotherUserIsBlockedWithoutRow() throws Exception {
        AppUser other = users.save(AppUser.of("policy-other@example.com", "다른 사람", 1L, UserRole.MEMBER));
        ConnectorConnection connection = ConnectorConnection.pending(other.id(), DEMO, agent, NOW);
        connection.ready(NOW);
        connections.save(connection);

        HttpResponse<String> response = ask("mcp__demo__list_scopes", "list_scopes");

        assertBlocked(response, "이 도구 호출의 실행 맥락을 확인하지 못해 실행하지 않았다.");
        assertThat(rows()).isZero();
    }

    @Test
    @DisplayName("다른 profile 의 토큰으로 서명해 보내면 그 profile 에서 session 을 찾지 못해 block 이고 줄이 없다")
    void tokenOfAnotherProfileIsBlockedWithoutRow() throws Exception {
        connect(true);
        String otherToken = tokens.issue(OTHER_PROFILE, "other").rawToken();

        HttpResponse<String> response =
                send(otherToken, body(otherToken, "mcp__demo__list_scopes", "list_scopes", root, newCall(), ARGS));

        assertBlocked(response, "이 도구 호출의 실행 맥락을 확인하지 못해 실행하지 않았다.");
        assertThat(rows()).isZero();
    }

    @Test
    @DisplayName("서명이나 모양이 틀리거나 JSON 이 아니거나 비어 있으면 403 CONNECTOR_POLICY_REJECTED 이고 줄이 없다")
    void badSignatureShapeNonJsonOrEmptyIs403() throws Exception {
        connect(true);
        ObjectNode wrongSig =
                McpCallSigner.policyBody(token, "mcp__demo__list_scopes", "list_scopes", root, root, newCall(), ARGS);
        wrongSig.put("sig", "0".repeat(64));
        // 읽기 도구로 서명한 본문의 등록 이름만 쓰기 도구로 바꾼다.
        ObjectNode swapped =
                McpCallSigner.policyBody(token, "mcp__demo__list_scopes", "list_scopes", root, root, newCall(), ARGS);
        swapped.put("hermes_tool", "mcp__demo__write_note");
        ObjectNode arrayArgs =
                McpCallSigner.policyBody(token, "mcp__demo__list_scopes", "list_scopes", root, root, newCall(), "[1]");

        for (String body : List.of(wrongSig.toString(), swapped.toString(), arrayArgs.toString(), "not json", "")) {
            HttpResponse<String> response = send(token, body);
            assertThat(response.statusCode())
                    .as("본문 %s 의 응답: %s", body, response.body())
                    .isEqualTo(403);
            assertThat(json.readTree(response.body()).path("code").asString())
                    .as("본문 %s", body)
                    .isEqualTo("CONNECTOR_POLICY_REJECTED");
        }
        assertThat(rows()).isZero();
    }

    @Test
    @DisplayName("토큰이 없거나 모르는 토큰이면 401 이고 줄이 없다")
    void missingOrUnknownTokenIs401() throws Exception {
        connect(true);
        String unknown = "unknown-" + UUID.randomUUID();

        HttpResponse<String> none =
                send(null, body(token, "mcp__demo__list_scopes", "list_scopes", root, newCall(), ARGS));
        HttpResponse<String> notIssued =
                send(unknown, body(unknown, "mcp__demo__list_scopes", "list_scopes", root, newCall(), ARGS));

        assertThat(none.statusCode()).isEqualTo(401);
        assertThat(notIssued.statusCode()).isEqualTo(401);
        assertThat(rows()).isZero();
    }

    @Test
    @DisplayName("응답 JSON 의 키는 decision, message, action_id 다")
    void responseKeysAreDecisionMessageAndActionId() throws Exception {
        connect(true);

        HttpResponse<String> response = ask("mcp__demo__list_scopes", "list_scopes");

        JsonNode body = json.readTree(response.body());
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("decision", "message", "action_id");
    }

    /** 주인의 연결을 만든다. {@code ready} 가 거짓이면 {@code PENDING} 으로 둔다. */
    private void connect(boolean ready) {
        ConnectorConnection connection = ConnectorConnection.pending(owner.id(), DEMO, agent, NOW);
        if (ready) {
            connection.ready(NOW);
        }
        connections.save(connection);
    }

    /** 뿌리 session 에서 부른 새 호출 하나를 주인의 토큰으로 서명해 보낸다. */
    private HttpResponse<String> ask(String hermesTool, String tool) throws Exception {
        return send(token, body(token, hermesTool, tool, root, newCall(), ARGS));
    }

    private static String body(
            String rawToken, String hermesTool, String tool, String rootSessionId, String toolCallId, String args) {
        return McpCallSigner.policyBody(rawToken, hermesTool, tool, rootSessionId, rootSessionId, toolCallId, args)
                .toString();
    }

    private HttpResponse<String> send(String bearer, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + PATH))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void assertBlocked(HttpResponse<String> response, String message) {
        assertThat(response.statusCode()).as("응답: %s", response.body()).isEqualTo(200);
        JsonNode body = json.readTree(response.body());
        assertThat(body.path("decision").asString()).isEqualTo("block");
        assertThat(body.path("message").asString()).isEqualTo(message);
        assertThat(body.path("action_id").isNull())
                .as("action_id: %s", body.path("action_id"))
                .isTrue();
    }

    private Map<String, Object> onlyRow() {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM connector_action");
        assertThat(rows).as("connector_action 의 줄").hasSize(1);
        return rows.get(0);
    }

    private int rows() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM connector_action", Integer.class);
    }

    private static String newCall() {
        return "call_" + UUID.randomUUID();
    }

    private static ConnectorManifest manifest(int schema, List<ConnectorTool> tools) {
        return new ConnectorManifest(
                DEMO, "검사용 메모", "", List.of(), "list_scopes", "demo", List.of(), false, schema, tools);
    }

    /** 검사가 시각을 옮기는 시계다. */
    private static final class MovingClock extends Clock {
        private Instant now;

        private MovingClock(Instant now) {
            this.now = now;
        }

        private synchronized void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public synchronized Instant instant() {
            return now;
        }
    }
}
