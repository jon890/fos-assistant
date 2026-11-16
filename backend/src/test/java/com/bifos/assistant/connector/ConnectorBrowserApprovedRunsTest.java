package com.bifos.assistant.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.conversation.infra.ConversationRepository;
import com.bifos.assistant.connector.action.application.ConnectorBrowserApprovedRuns;
import com.bifos.assistant.connector.action.infra.ConnectorActionRepository;
import com.bifos.assistant.connector.connection.application.ConnectorCatalogCache;
import com.bifos.assistant.hermes.application.dto.ConnectorManifest;
import com.bifos.assistant.mcp.McpCallSigner;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** 사람이 브라우저를 넘겨받기 전에 묻는 「브라우저를 쓰는 승인한 실행이 도는가」 를 실제 쿼리로 본다. 뜻은 spec 의 REQ6 이다. */
@BackendIntegrationTest
class ConnectorBrowserApprovedRunsTest {
    private static final String PROFILE = "browser-approved-runs-test";
    private static final Instant NOW = Instant.parse("2026-11-16T03:00:00Z");

    @Autowired
    ConnectorActionRepository actions;

    @Autowired
    AppUserRepository users;

    @Autowired
    AgentRepository agents;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    JdbcTemplate jdbc;

    private final ConnectorCatalogCache details = mock(ConnectorCatalogCache.class);
    private ConnectorBrowserApprovedRuns runs;
    private AppUser owner;
    private AppUser stranger;
    private Agent agent;
    private Conversation conversation;


    @BeforeEach
    void setUp() {
        cleanup();
        owner = users.save(AppUser.of("approved-runs-owner@example.com", "주인", 1L, UserRole.MEMBER));
        stranger = users.save(AppUser.of("approved-runs-stranger@example.com", "남", 1L, UserRole.MEMBER));
        agent = McpCallSigner.agentFor(agents, PROFILE);
        conversation = conversations.save(Conversation.startedBy(owner.id(), "승인 실행 대화", agent.id(), NOW));
        when(details.find("naver-shopping")).thenReturn(Optional.of(manifest("naver-shopping", true)));
        when(details.find("tossinvest")).thenReturn(Optional.of(manifest("tossinvest", false)));
        when(details.find("unreadable")).thenThrow(new IllegalStateException("catalog down"));
        runs = new ConnectorBrowserApprovedRuns(actions, details);
    }


    @AfterEach
    void tearDown() {
        cleanup();
    }


    @Test
    @DisplayName("브라우저 플러그인의 실행 중인 구매 승인과 카탈로그를 읽지 못한 실행 중인 금융 승인은 참이다")
    void runningBrowserPurchaseIsRunning() {
        insert(owner, "naver-shopping", "EXECUTING", "PURCHASE");
        assertThat(runs.running(owner.id())).isTrue();

        cleanupActions();
        insert(owner, "unreadable", "EXECUTING", "FINANCIAL");
        assertThat(runs.running(owner.id())).isTrue();
    }


    @Test
    @DisplayName("끝난 승인, 기다리는 승인, 일반 쓰기, 브라우저를 쓰지 않는 플러그인, 남의 줄은 거짓이다")
    void otherRowsAreNotRunning() {
        insert(owner, "naver-shopping", "SUCCEEDED", "PURCHASE");
        insert(owner, "naver-shopping", "PENDING", "PURCHASE");
        insert(owner, "naver-shopping", "EXECUTING", "WRITE");
        insert(owner, "tossinvest", "EXECUTING", "FINANCIAL");
        insert(stranger, "naver-shopping", "EXECUTING", "PURCHASE");

        assertThat(runs.running(owner.id())).isFalse();
    }


    private void insert(AppUser user, String connectorId, String status, String risk) {
        UUID publicId = UUID.randomUUID();
        byte[] bytes = ByteBuffer.allocate(16)
            .putLong(publicId.getMostSignificantBits())
            .putLong(publicId.getLeastSignificantBits())
            .array();
        jdbc.update(
            "INSERT INTO connector_action (public_id, user_id, agent_id, connector_id, tool_name, hermes_tool, risk,"
                + " decision, passed, status, origin_execution_id, conversation_id, dedupe_key, args_sha256, created_at)"
                + " VALUES (?, ?, ?, ?, 'tool', 'mcp__x__tool', ?, 'ALLOWED', TRUE, ?, 1, ?, ?, ?, ?)",
            bytes,
            user.id(),
            agent.id(),
            connectorId,
            risk,
            status,
            conversation.id(),
            "approved-" + publicId,
            "0".repeat(64),
            Timestamp.from(NOW));
    }


    private static ConnectorManifest manifest(String id, boolean ownerBrowser) {
        ConnectorManifest base = new ConnectorManifest(id, id, "", List.of(), "check", id, 2, List.of());
        return new ConnectorManifest(base.id(), base.title(), base.description(), base.fields(), base.verifyTool(),
            base.mcpServer(), base.schema(), base.tools(), base.skills(), base.appearance(), base.singleBinding(),
            ownerBrowser, base.ownerBrowserLoginUrl(), base.executionGuard(), base.executionGuardManifestSha256(),
            base.snapshot(), base.unknownErrors(), base.defaultToolPolicy(), base.eventWatch());
    }


    private void cleanupActions() {
        jdbc.update("DELETE FROM connector_action WHERE dedupe_key LIKE 'approved-%'");
    }


    private void cleanup() {
        cleanupActions();
        McpCallSigner.clearRuns(jdbc, List.of(PROFILE));
        jdbc.update("DELETE FROM conversation WHERE title = '승인 실행 대화'");
        jdbc.update("DELETE FROM app_user WHERE email IN ('approved-runs-owner@example.com', 'approved-runs-stranger@example.com')");
    }
}
