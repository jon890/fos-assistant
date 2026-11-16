package com.bifos.assistant.connector.approval.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.browser.domain.type.UserBrowserStatus;
import com.bifos.assistant.browser.infra.UserBrowserRepository;
import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.conversation.infra.ConversationRepository;
import com.bifos.assistant.connector.connection.application.ConnectorCatalogCache;
import com.bifos.assistant.connector.contentdraft.application.ContentDraftSaveApproval;
import com.bifos.assistant.hermes.application.dto.ConnectorManifest;
import com.bifos.assistant.hermes.application.dto.ConnectorTool;
import com.bifos.assistant.hermes.application.port.HermesConnectorClient;
import com.bifos.assistant.mcp.McpCallSigner;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.FakeBrowserRuntime;
import com.bifos.assistant.testsupport.OverrideProperties;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 「내 브라우저」 를 쓰는 플러그인의 승인에서 브라우저를 켜지 못하면 줄을 건드리지 않고 거절하는지 실제 승인 서비스와 DB 로 본다.
 * 뜻은 {@code docs/features/browser-cowork/spec.md} 의 REQ2 다. 켜기 실패는 대역 proxy 가 만든다.
 */
@BackendIntegrationTest
@OverrideProperties("assistant.browser.enabled=true")
class ApprovalBrowserUnavailableTest {
    private static final String PROFILE = "approval-browser-test";
    private static final String EMAIL = "approval-browser@example.com";

    @Autowired
    ConnectorActionService service;

    @Autowired
    ContentDraftSaveApproval contentDrafts;

    @Autowired
    FakeBrowserRuntime runtime;

    @Autowired
    HermesConnectorClient connector;

    @Autowired
    UserBrowserRepository browsers;

    @Autowired
    AppUserRepository users;

    @Autowired
    AgentRepository agents;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    Clock clock;

    @Autowired
    ConnectorCatalogCache catalog;

    private AppUser owner;
    private Agent agent;
    private Conversation conversation;


    @BeforeEach
    void setUp() {
        cleanup();
        runtime.reset();
        when(connector.readCatalog()).thenReturn(List.of(
            manifest("naver-shopping", true), manifest("naver-blog", true), manifest("gmail", false)));
        resetCatalog();
        clearInvocations(connector);
        owner = users.save(AppUser.of(EMAIL, "주인", 1L, UserRole.MEMBER));
        agent = McpCallSigner.agentFor(agents, PROFILE);
        conversation = conversations.save(Conversation.startedBy(owner.id(), "승인 브라우저 대화", agent.id(), clock.instant()));
        runtime.failingActions().add("create");
    }


    @AfterEach
    void tearDown() {
        runtime.reset();
        cleanup();
    }


    @Test
    @DisplayName("브라우저 플러그인의 쓰기 줄은 켜지 못하면 거절되고 줄과 피드백이 그대로이며, 켤 수 있게 되면 다시 승인된다")
    void refusesWriteWithoutTouchingRowAndAllowsRetry() {
        UUID action = pending("naver-shopping", "WRITE", "write_note", "mcp__naver_shopping__write_note", null);

        assertUnavailable(() -> service.approve(user(), action, null, false));
        assertThat(status(action)).isEqualTo("PENDING");
        assertThat(feedbackRows()).isZero();
        verify(connector, never()).execute(any(), any(), any(), any());

        runtime.failingActions().clear();
        service.approve(user(), action, null, false);

        assertThat(status(action)).isNotEqualTo("PENDING");
        assertThat(browsers.findByUserId(owner.id()).orElseThrow().status()).isEqualTo(UserBrowserStatus.RUNNING);
    }


    @Test
    @DisplayName("구매 줄도 금융 승인으로 넘기기 전에 거절되고 오늘 구매 합계에 들지 않는다")
    void refusesPurchaseBeforeFinancialApproval() {
        UUID action = pending("naver-shopping", "PURCHASE", "fill_checkout", "mcp__naver_shopping__fill_checkout", null);

        assertUnavailable(() -> service.approve(user(), action, null, false));

        assertThat(status(action)).isEqualTo("PENDING");
        assertThat(feedbackRows()).isZero();
        assertThat(executions()).isZero();
    }


    @Test
    @DisplayName("원고 저장 승인도 켜지 못하면 거절되고 줄이 그대로다")
    void refusesContentDraftSave() {
        UUID action = pending("naver-blog", "WRITE", "save_draft", "mcp__naver_blog__save_draft", null);

        assertUnavailable(() -> contentDrafts.approve(user(), action, null, false));

        assertThat(status(action)).isEqualTo("PENDING");
    }


    @Test
    @DisplayName("상한 변경 줄은 브라우저를 쓰지 않아 켜지 않는다")
    void skipsLimitChange() {
        UUID action = pending("naver-shopping", "WRITE", FinancialLimitChanges.TOOL, "fos_order_limit_change", "ORDER_AMOUNT_KRW");

        try {
            service.approve(user(), action, null, false);
        } catch (ApiException ex) {
            assertThat(ex.code()).isNotEqualTo(ErrorCode.CONNECTOR_BROWSER_UNAVAILABLE);
        } catch (RuntimeException ex) {
            // 대역 줄이라 그 뒤 판정이 실패할 수 있다. 이 테스트는 브라우저를 켜지 않았는지만 본다
        }

        assertThat(browsers.findByUserId(owner.id())).isEmpty();
    }


    @Test
    @DisplayName("브라우저를 쓰지 않는 플러그인의 줄은 켜지 않는다")
    void skipsPluginWithoutBrowser() {
        UUID action = pending("gmail", "WRITE", "send_mail", "mcp__gmail__send_mail", null);

        try {
            service.approve(user(), action, null, false);
        } catch (ApiException ex) {
            assertThat(ex.code()).isNotEqualTo(ErrorCode.CONNECTOR_BROWSER_UNAVAILABLE);
        } catch (RuntimeException ex) {
            // 대역 줄이라 그 뒤 판정이 실패할 수 있다. 이 테스트는 브라우저를 켜지 않았는지만 본다
        }

        assertThat(browsers.findByUserId(owner.id())).isEmpty();
    }


    private static void assertUnavailable(Runnable approve) {
        assertThatThrownBy(approve::run)
            .isInstanceOf(ApiException.class)
            .extracting(ex -> ((ApiException)ex).code())
            .isEqualTo(ErrorCode.CONNECTOR_BROWSER_UNAVAILABLE);
    }


    private UUID pending(String connectorId, String risk, String tool, String hermesTool, String limitKind) {
        UUID publicId = UUID.randomUUID();
        byte[] bytes = ByteBuffer.allocate(16)
            .putLong(publicId.getMostSignificantBits())
            .putLong(publicId.getLeastSignificantBits())
            .array();
        jdbc.update(
            "INSERT INTO connector_action (public_id, user_id, agent_id, connector_id, tool_name, hermes_tool, risk,"
                + " approval_mode, decision, passed, status, origin_execution_id, conversation_id, dedupe_key,"
                + " args_json, args_sha256, expires_at, limit_kind, created_at)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, 'REQUIRED', 'NEEDS_APPROVAL', FALSE, 'PENDING', 0, ?, ?, '{}', ?, ?, ?, ?)",
            bytes,
            owner.id(),
            agent.id(),
            connectorId,
            tool,
            hermesTool,
            risk,
            conversation.id(),
            "approval-browser-" + publicId,
            "0".repeat(64),
            Timestamp.from(clock.instant().plus(Duration.ofMinutes(10))),
            limitKind,
            Timestamp.from(clock.instant()));
        return publicId;
    }


    private String status(UUID action) {
        return jdbc.queryForObject(
            "SELECT status FROM connector_action WHERE dedupe_key = ?", String.class, "approval-browser-" + action);
    }


    private long feedbackRows() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM decision_feedback_event WHERE user_id = ?", Long.class, owner.id());
    }


    private long executions() {
        return jdbc.queryForObject(
            "SELECT COUNT(*) FROM connector_action_execution e JOIN connector_action a ON a.id = e.action_id WHERE a.user_id = ?",
            Long.class, owner.id());
    }


    private CurrentUser user() {
        return new CurrentUser(owner.id(), owner.email(), "주인", 1L, UserRole.MEMBER);
    }


    private static ConnectorManifest manifest(String id, boolean ownerBrowser) {
        ConnectorManifest base = new ConnectorManifest(id, id, "", List.of(), "check", id, 2,
            List.of(new ConnectorTool("check", "READ", "none", null, null)));
        return new ConnectorManifest(base.id(), base.title(), base.description(), base.fields(), base.verifyTool(),
            base.mcpServer(), base.schema(), base.tools(), base.skills(), base.appearance(), base.singleBinding(),
            ownerBrowser, base.ownerBrowserLoginUrl(), base.executionGuard(), base.executionGuardManifestSha256(),
            base.snapshot(), base.unknownErrors(), base.defaultToolPolicy(), base.eventWatch());
    }


    /** 카탈로그 기억을 비워 이 테스트의 대역 카탈로그를 읽게 한다. 기억은 시험 시계로 재므로 기다려서는 비지 않는다. */
    private void resetCatalog() {
        try {
            var field = ConnectorCatalogCache.class.getDeclaredField("readAt");
            field.setAccessible(true);
            field.set(catalog, null);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }


    private void cleanup() {
        jdbc.update("DELETE FROM decision_feedback_event WHERE user_id IN (SELECT id FROM app_user WHERE email = ?)", EMAIL);
        jdbc.update("DELETE FROM connector_action WHERE dedupe_key LIKE 'approval-browser-%'");
        jdbc.update("DELETE FROM user_browser WHERE user_id IN (SELECT id FROM app_user WHERE email = ?)", EMAIL);
        McpCallSigner.clearRuns(jdbc, List.of(PROFILE));
        jdbc.update("DELETE FROM conversation WHERE title = '승인 브라우저 대화'");
        jdbc.update("DELETE FROM app_user WHERE email = ?", EMAIL);
    }
}
