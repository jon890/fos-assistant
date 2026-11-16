package com.bifos.assistant.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.conversation.infra.ConversationRepository;
import com.bifos.assistant.connector.action.application.ConnectorBrowserWaitDemand;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 브라우저 자동 중지가 묻는 「사람을 기다리는가」 를 실제 판정 줄 쿼리로 본다. 뜻은 {@code docs/features/browser-cowork/spec.md} 의
 * REQ1 과 설계의 「PR1 사람을 기다리는 동안 브라우저 유지」 다.
 */
@BackendIntegrationTest
class ConnectorBrowserWaitDemandTest {
    private static final String PROFILE = "browser-wait-test";
    private static final Instant NOW = Instant.parse("2026-11-16T03:00:00Z");
    private static final Instant STARTED = NOW.minus(Duration.ofHours(1));

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
    private ConnectorBrowserWaitDemand demand;
    private AppUser owner;
    private AppUser stranger;
    private Agent agent;
    private Conversation conversation;


    @BeforeEach
    void setUp() {
        cleanup();
        owner = users.save(AppUser.of("browser-wait-owner@example.com", "주인", 1L, UserRole.MEMBER));
        stranger = users.save(AppUser.of("browser-wait-stranger@example.com", "남", 1L, UserRole.MEMBER));
        agent = McpCallSigner.agentFor(agents, PROFILE);
        conversation = conversations.save(Conversation.startedBy(owner.id(), "기다림 대화", agent.id(), NOW));
        when(details.find("naver-shopping")).thenReturn(Optional.of(manifest("naver-shopping", true)));
        when(details.find("gmail")).thenReturn(Optional.of(manifest("gmail", false)));
        when(details.find("unreadable")).thenThrow(new IllegalStateException("catalog down"));
        demand = new ConnectorBrowserWaitDemand(actions, details, Clock.fixed(NOW, ZoneOffset.UTC));
    }


    @AfterEach
    void tearDown() {
        cleanup();
    }


    @Test
    @DisplayName("이번 켜기 뒤에 생긴 대화의 열린 넘기기가 있으면 기다린다")
    void waitsForOpenHandoff() {
        handoff(owner, conversation.id(), NOW.minus(Duration.ofMinutes(5)), null);

        assertThat(demand.waiting(owner.id(), STARTED)).isTrue();
    }


    @Test
    @DisplayName("닫힌 넘기기, 대화 없는 넘기기, 켜기 전의 넘기기, 남의 넘기기는 세지 않는다")
    void ignoresHandoffsThatCannotBeWaitedOn() {
        handoff(owner, conversation.id(), NOW.minus(Duration.ofMinutes(5)), NOW.minus(Duration.ofMinutes(1)));
        handoff(owner, null, NOW.minus(Duration.ofMinutes(5)), null);
        handoff(owner, conversation.id(), STARTED.minus(Duration.ofMinutes(1)), null);
        handoff(stranger, conversation.id(), NOW.minus(Duration.ofMinutes(5)), null);

        assertThat(demand.waiting(owner.id(), STARTED)).isFalse();
    }


    @Test
    @DisplayName("브라우저 플러그인의 기다리는 승인과 카탈로그를 읽지 못한 기다리는 승인은 기다림이다")
    void waitsForBrowserPluginApproval() {
        pending(owner, "naver-shopping", NOW.plus(Duration.ofMinutes(10)));
        assertThat(demand.waiting(owner.id(), STARTED)).isTrue();

        cleanupActions();
        pending(owner, "unreadable", NOW.plus(Duration.ofMinutes(10)));
        assertThat(demand.waiting(owner.id(), STARTED)).isTrue();
    }


    @Test
    @DisplayName("브라우저를 쓰지 않는 플러그인, 기다리는 시간이 지난 승인, 남의 승인은 기다림이 아니다")
    void ignoresOtherApprovals() {
        pending(owner, "gmail", NOW.plus(Duration.ofMinutes(10)));
        pending(owner, "naver-shopping", NOW.minus(Duration.ofMinutes(1)));
        pending(stranger, "naver-shopping", NOW.plus(Duration.ofMinutes(10)));

        assertThat(demand.waiting(owner.id(), STARTED)).isFalse();
    }


    private void handoff(AppUser user, Long conversationId, Instant at, Instant closedAt) {
        insert(user, "naver-shopping", null, null, conversationId, at, closedAt);
    }


    private void pending(AppUser user, String connectorId, Instant expiresAt) {
        insert(user, connectorId, "PENDING", expiresAt, conversation.id(), null, null);
    }


    private void insert(
        AppUser user, String connectorId, String status, Instant expiresAt, Long conversationId, Instant handoffAt,
        Instant closedAt) {
        UUID publicId = UUID.randomUUID();
        byte[] bytes = ByteBuffer.allocate(16)
            .putLong(publicId.getMostSignificantBits())
            .putLong(publicId.getLeastSignificantBits())
            .array();
        jdbc.update(
            "INSERT INTO connector_action (public_id, user_id, agent_id, connector_id, tool_name, hermes_tool, risk,"
                + " decision, passed, status, origin_execution_id, conversation_id, dedupe_key, args_sha256, expires_at,"
                + " handoff_need, handoff_tab, handoff_at, handoff_closed_at, created_at)"
                + " VALUES (?, ?, ?, ?, 'tool', 'mcp__x__tool', 'WRITE', 'ALLOWED', TRUE, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            bytes,
            user.id(),
            agent.id(),
            connectorId,
            status,
            conversationId,
            "wait-" + publicId,
            "0".repeat(64),
            expiresAt == null ? null : Timestamp.from(expiresAt),
            handoffAt == null ? null : "LOGIN",
            handoffAt == null ? null : "TAB1",
            handoffAt == null ? null : Timestamp.from(handoffAt),
            closedAt == null ? null : Timestamp.from(closedAt),
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
        jdbc.update("DELETE FROM connector_action WHERE dedupe_key LIKE 'wait-%'");
    }


    private void cleanup() {
        cleanupActions();
        McpCallSigner.clearRuns(jdbc, List.of(PROFILE));
        jdbc.update("DELETE FROM conversation WHERE title = '기다림 대화'");
        jdbc.update("DELETE FROM app_user WHERE email IN ('browser-wait-owner@example.com', 'browser-wait-stranger@example.com')");
    }
}
