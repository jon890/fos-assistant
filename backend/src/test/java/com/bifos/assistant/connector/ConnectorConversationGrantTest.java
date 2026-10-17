package com.bifos.assistant.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.connector.application.ConnectorActionResultSource;
import com.bifos.assistant.connector.application.ConnectorActionService;
import com.bifos.assistant.connector.application.ConnectorBindingService;
import com.bifos.assistant.connector.application.ConnectorPolicyService;
import com.bifos.assistant.connector.application.model.ConnectorActionResult;
import com.bifos.assistant.connector.application.model.ConnectorActionView;
import com.bifos.assistant.connector.application.model.ConnectorConversationGrantView;
import com.bifos.assistant.connector.application.model.ConnectorPolicyAnswer;
import com.bifos.assistant.connector.domain.ConnectorBinding;
import com.bifos.assistant.connector.domain.ConnectorConnection;
import com.bifos.assistant.connector.domain.type.ActionDecision;
import com.bifos.assistant.connector.domain.type.ActionStatus;
import com.bifos.assistant.connector.domain.type.GrantPeriod;
import com.bifos.assistant.connector.infra.ConnectorActionRepository;
import com.bifos.assistant.connector.infra.ConnectorBindingRepository;
import com.bifos.assistant.connector.infra.ConnectorConnectionRepository;
import com.bifos.assistant.connector.infra.ConnectorConversationGrantRepository;
import com.bifos.assistant.feedback.application.port.FeedbackConversations;
import com.bifos.assistant.hermes.application.port.ConnectorExecutionUnknown;
import com.bifos.assistant.hermes.application.port.HermesConnectorClient;
import com.bifos.assistant.hermes.application.dto.CallResult;
import com.bifos.assistant.hermes.application.dto.ConnectorCallError;
import com.bifos.assistant.hermes.application.dto.ConnectorManifest;
import com.bifos.assistant.hermes.application.dto.ConnectorTool;
import com.bifos.assistant.hermes.application.dto.ConnectorUnknownDetail;
import com.bifos.assistant.hermes.application.dto.ConnectorUnknownErrors;
import com.bifos.assistant.mcp.McpCallSigner;
import com.bifos.assistant.mcp.infra.AgentTokenRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.ConnectorCatalogTimes;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * 승인 카드의 「이 대화에서 계속 허용」 이 그 대화의 그 에이전트 실행에서만 통하는지 실제 DB 로 확인한다(ADR-20261012 /
 * conversation-grant).
 *
 * <p>계약은 {@code docs/features/connector-policy.md} 의 「대화 허락」 이다. 대시보드의 실행 경로는 대역이 답한다.
 */
@BackendIntegrationTest
class ConnectorConversationGrantTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PROFILE = "conversation-grant-owner";
    /** 같은 주인의 다른 에이전트 profile 이다. 같은 연결을 붙여 같은 대화에서 위임 자식으로 돈다. */
    private static final String OTHER_PROFILE = "conversation-grant-other";

    private static final String DEMO = "demo-notes";
    private static final String WRITE = "write_note";
    /** 상시 허락은 닫았지만 계정 밖으로 나가지 않는 도구다. 네이버 임시저장 글 불러오기와 같은 선언이다. */
    private static final String DRAFT = "read_draft";
    /** 계정 밖의 사람에게 보내는 도구다. */
    private static final String MAIL = "mail_note";
    private static final String ALWAYS = "send_note";
    /** 상시 허락을 닫고 밖으로 나가지 않지만 대화 허락을 선언하지 않은 도구다. Gmail 의 필터 도구와 같은 선언이다. */
    private static final String FILTER = "create_filter";
    private static final String ARGS = "{\"text\":\"안녕\"}";

    private static final ConnectorManifest DECLARING = new ConnectorManifest(
        DEMO,
        "검사용 메모",
        "",
        List.of(),
        "list_scopes",
        "demo",
        2,
        List.of(
            new ConnectorTool("list_scopes", "READ", "none", null, null, List.of(), null, Map.of(), false),
            new ConnectorTool(WRITE, "WRITE", "required", "메모 쓰기", true, List.of(), null, Map.of(), false, true),
            new ConnectorTool(DRAFT, "WRITE", "required", "글 불러오기", false, List.of(), null, Map.of(), false, true),
            new ConnectorTool(MAIL, "WRITE", "required", "메모 보내기", false, List.of(), null, Map.of(), true),
            new ConnectorTool(ALWAYS, "WRITE", "always", null, false, List.of(), null, Map.of(), false),
            new ConnectorTool(FILTER, "WRITE", "required", "필터 만들기", false, List.of(), null, Map.of(), false)));

    @Autowired
    ConnectorPolicyService policies;

    @Autowired
    ConnectorActionService service;

    @Autowired
    ConnectorActionResultSource resultSource;

    @Autowired
    ConnectorBindingService bindingService;

    @Autowired
    ConnectorActionRepository actions;

    @Autowired
    ConnectorConversationGrantRepository conversationGrants;

    @Autowired
    ConnectorConnectionRepository connections;

    @Autowired
    ConnectorBindingRepository bindings;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    AppUserRepository users;

    @Autowired
    AgentRepository agents;

    @Autowired
    AgentTokenRepository tokens;

    @Autowired
    AgentExecutionRepository executions;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    HermesConnectorClient connector;

    @Autowired
    FeedbackConversations feedbackConversations;

    @Autowired
    PlatformTransactionManager transactionManager;

    private AppUser owner;
    private CurrentUser me;
    private Agent agent;
    private Agent other;
    private ConnectorConnection connection;
    private Conversation conversation;
    private String root;


    @BeforeEach
    void setUp() {
        deleteRows();
        doReturn(true).when(feedbackConversations).isActive(anyLong());
        ConnectorCatalogTimes.expire();
        when(connector.readCatalog()).thenReturn(List.of(DECLARING));
        when(connector.execute(anyString(), anyString(), anyString(), anyString()))
            .thenReturn(CallResult.success(JSON.readTree("{\"saved\":true}")));

        owner = users.save(AppUser.of("conversation-grant@example.com", "주인", 1L, UserRole.MEMBER));
        me = currentUser(owner);
        agent = agents.save(agent(PROFILE));
        other = agents.save(agent(OTHER_PROFILE));
        Instant connectedAt = Instant.parse("2026-10-01T00:00:00Z");
        ConnectorConnection pending = ConnectorConnection.pending(owner.id(), DEMO, connectedAt);
        pending.ready(connectedAt);
        connection = connections.save(pending);
        bind(agent);
        bind(other);
        conversation = startConversation();
        root = startRun(agent, PROFILE, conversation.id(), null);
    }


    @AfterEach
    void tearDown() {
        deleteRows();
    }


    @Test
    @DisplayName("이 대화에서 계속 허용하고 승인하면 한 번 실행하고, 같은 대화의 같은 에이전트가 다시 부르면 승인 없이 통과한다")
    void conversationGrantLetsLaterCallsInTheSameConversationPass() {
        UUID actionId = ask(root, WRITE, ARGS).actionId();

        ConnectorActionView approved = service.approve(me, actionId, null, true);
        ConnectorPolicyAnswer next = ask(root, WRITE, "{\"text\":\"다음 글\"}");

        assertThat(approved.status()).isEqualTo(ActionStatus.SUCCEEDED);
        verify(connector, times(1)).execute(anyString(), anyString(), anyString(), anyString());
        assertThat(next.allowed()).isTrue();
        assertThat(next.actionId()).isNull();
        assertThat(actions.findAll())
            .filteredOn(action -> action.status() == null)
            .singleElement()
            .satisfies(action -> {
                assertThat(action.decision()).isEqualTo(ActionDecision.ALLOWED);
                assertThat(action.argsJson()).isNull();
            });
        assertThat(conversationGrants.findAll()).singleElement().satisfies(grant -> {
            assertThat(grant.conversationId()).isEqualTo(conversation.id());
            assertThat(grant.agentId()).isEqualTo(agent.id());
            assertThat(grant.expiresAt()).isEqualTo(actions.findById(grant.actionId()).orElseThrow().decidedAt().plus(Duration.ofHours(24)));
        });
        assertThat(service.conversationGrants(me, conversation.id()))
            .singleElement()
            .satisfies(grant -> assertThat(grant.title()).isEqualTo("메모 쓰기"));
        // 상시 허락이 아니므로 허락 목록에는 없다.
        assertThat(service.grants(me)).isEmpty();
    }


    @Test
    @DisplayName("대화 허락과 함께 승인한 결과는 알림 줄과 모델 입력이 그 허락을 함께 알린다")
    void resultCarriesConversationGrant() {
        UUID granted = ask(root, WRITE, ARGS).actionId();
        service.approve(me, granted, null, true);
        UUID plain = ask(root, DRAFT, ARGS).actionId();
        service.approve(me, plain, null, false);

        assertThat(service.resultsFor(conversation.id(), owner.id(), List.of(granted, plain)))
            .extracting(ConnectorActionResult::actionId, ConnectorActionResult::conversationGranted)
            .containsExactly(
                tuple(granted, true), tuple(plain, false));
        String until = DateTimeFormatter.ofPattern("M월 d일 HH:mm")
            .withZone(ZoneId.of("Asia/Seoul"))
            .format(conversationGrants.findAll().getFirst().expiresAt());
        assertThat(resultSource.resultsFor(conversation.id(), owner.id(), List.of(granted.toString(), plain.toString())))
            .satisfiesExactly(
                result -> {
                    assertThat(result.notice()).contains(until + "까지 같은 동작을 다시 묻지 않고 실행해요");
                    assertThat(result.input()).contains("이 에이전트가", until + "까지");
                },
                result -> assertThat(result.notice()).doesNotContain("묻지 않고"));
    }


    @Test
    @DisplayName("다시 전할 때 이미 거두었거나 기간이 지난 허락은 결과에 알리지 않는다")
    void redeliveredResultDropsEndedConversationGrant() {
        UUID revoked = ask(root, WRITE, ARGS).actionId();
        service.approve(me, revoked, null, true);
        Long revokedGrant = conversationGrants.findAll().getFirst().id();
        service.revokeConversationGrant(me, conversation.id(), revokedGrant);
        UUID expired = ask(root, DRAFT, ARGS).actionId();
        service.approve(me, expired, null, true);
        jdbc.update(
            "UPDATE connector_conversation_grant SET expires_at = ? WHERE id <> ?",
            Instant.parse("2020-01-01T00:00:00Z"),
            revokedGrant);

        assertThat(service.resultsFor(conversation.id(), owner.id(), List.of(revoked, expired)))
            .extracting(ConnectorActionResult::actionId, ConnectorActionResult::conversationGranted)
            .containsExactly(tuple(revoked, false), tuple(expired, false));
    }


    @Test
    @DisplayName("대화 허락은 다른 대화의 실행에서 통하지 않고 그 대화에서는 다시 승인을 기다린다")
    void conversationGrantDoesNotPassInAnotherConversation() {
        service.approve(me, ask(root, WRITE, ARGS).actionId(), null, true);
        String otherRoot = startRun(agent, PROFILE, startConversation().id(), null);

        ConnectorPolicyAnswer elsewhere = ask(otherRoot, WRITE, ARGS);

        assertThat(elsewhere.allowed()).isFalse();
        assertThat(elsewhere.actionId()).isNotNull();
        assertThat(ask(root, WRITE, "{\"text\":\"원래 대화\"}").allowed()).isTrue();
    }


    @Test
    @DisplayName("대화 허락은 같은 대화라도 다른 에이전트의 위임 실행에서 통하지 않는다")
    void conversationGrantDoesNotPassForAnotherAgentInTheSameConversation() {
        service.approve(me, ask(root, WRITE, ARGS).actionId(), null, true);
        AgentExecution parent = executions.findAll().stream()
            .filter(run -> root.equals(run.hermesSessionId()))
            .findFirst()
            .orElseThrow();
        String childRoot = startRun(other, OTHER_PROFILE, conversation.id(), parent);

        ConnectorPolicyAnswer child = policies.decide(
            OTHER_PROFILE, childRoot, childRoot, "call_" + UUID.randomUUID(), "mcp__demo__" + WRITE, WRITE, ARGS);

        assertThat(child.allowed()).isFalse();
        assertThat(child.actionId()).isNotNull();

        service.approve(me, child.actionId(), null, true);
        assertThat(service.conversationGrants(me, conversation.id()))
            .extracting(ConnectorConversationGrantView::agentName)
            .as("대화의 에이전트에 준 허락은 이름을 싣지 않고 위임 자식의 에이전트에 준 허락만 싣는다")
            .containsExactly(null, other.name());
    }


    @Test
    @DisplayName("밖으로 나가는 도구와 늘 승인을 받는 도구, 대화 허락을 선언하지 않은 도구는 대화 허락을 줄 수 없고 VALIDATION_FAILED 이며 줄은 PENDING 그대로다")
    void undeclaredOutboundAndAlwaysToolsCannotBeGrantedForTheConversation() {
        for (String tool : List.of(MAIL, ALWAYS, FILTER)) {
            UUID actionId = ask(root, tool, ARGS).actionId();
            ConnectorActionView listed = listed(actionId);
            assertThat(listed.conversationGrantAllowed()).as(tool).isFalse();
            assertThat(listed.grantAllowed()).as(tool).isFalse();

            assertCode(() -> service.approve(me, actionId, null, true), ErrorCode.VALIDATION_FAILED);

            assertThat(actions.findAll())
                .filteredOn(action -> action.publicId().equals(actionId))
                .singleElement()
                .satisfies(action -> assertThat(action.status()).isEqualTo(ActionStatus.PENDING));
        }
        assertThat(conversationGrants.findAll()).isEmpty();
        verify(connector, never()).execute(anyString(), anyString(), anyString(), anyString());
    }


    @Test
    @DisplayName("상시 허락을 닫았지만 밖으로 나가지 않는 도구는 대화 허락만 받고, 기간을 실은 승인은 여전히 VALIDATION_FAILED 다")
    void closedGrantInsideToolTakesOnlyConversationGrant() {
        UUID first = ask(root, DRAFT, ARGS).actionId();
        ConnectorActionView listed = listed(first);
        assertThat(listed.conversationGrantAllowed()).isTrue();
        assertThat(listed.grantAllowed()).isFalse();

        assertCode(() -> service.approve(me, first, GrantPeriod.HOUR, false), ErrorCode.VALIDATION_FAILED);
        service.approve(me, first, null, true);

        assertThat(ask(root, DRAFT, "{\"text\":\"다른 글\"}").allowed()).isTrue();
    }


    @Test
    @DisplayName("기간과 대화 허락을 함께 실은 승인은 VALIDATION_FAILED 이고 실행하지 않는다")
    void standingAndConversationGrantTogetherIsRejected() {
        UUID actionId = ask(root, WRITE, ARGS).actionId();

        assertCode(() -> service.approve(me, actionId, GrantPeriod.HOUR, true), ErrorCode.VALIDATION_FAILED);

        assertThat(conversationGrants.findAll()).isEmpty();
        verify(connector, never()).execute(anyString(), anyString(), anyString(), anyString());
    }


    @Test
    @DisplayName("대화를 지우면 그 대화의 허락은 더 통하지 않는다")
    void deletedConversationGrantNoLongerPasses() {
        service.approve(me, ask(root, WRITE, ARGS).actionId(), null, true);
        jdbc.update("UPDATE conversation SET deleted_at = ? WHERE id = ?", Instant.now(), conversation.id());

        ConnectorPolicyAnswer next = ask(root, WRITE, "{\"text\":\"지운 뒤\"}");

        assertThat(next.allowed()).isFalse();
        assertThat(next.actionId()).isNotNull();
    }


    @Test
    @DisplayName("기간이 지나거나 사용자가 거두면 다시 승인을 기다리고, 남의 허락이나 다른 대화의 번호로는 거두지 못한다")
    void expiredOrRevokedConversationGrantNoLongerPasses() {
        service.approve(me, ask(root, WRITE, ARGS).actionId(), null, true);
        Long grantId = service.conversationGrants(me, conversation.id()).getFirst().grantId();
        CurrentUser stranger = currentUser(
            users.save(AppUser.of("conversation-grant-other@example.com", "다른 사람", 1L, UserRole.ADMIN)));

        assertCode(() -> service.revokeConversationGrant(stranger, conversation.id(), grantId),
            ErrorCode.CONNECTOR_ACTION_NOT_FOUND);
        assertCode(() -> service.revokeConversationGrant(me, conversation.id() + 1000, grantId),
            ErrorCode.CONNECTOR_ACTION_NOT_FOUND);
        assertThat(ask(root, WRITE, "{\"text\":\"거두기 전\"}").allowed()).isTrue();
        service.revokeConversationGrant(me, conversation.id(), grantId);

        assertThat(ask(root, WRITE, "{\"text\":\"거둔 뒤\"}").allowed()).isFalse();
        assertThat(service.conversationGrants(me, conversation.id())).isEmpty();

        service.approve(me, ask(root, WRITE, ARGS).actionId(), null, true);
        jdbc.update("UPDATE connector_conversation_grant SET expires_at = ?", Instant.parse("2020-01-01T00:00:00Z"));
        assertThat(ask(root, WRITE, "{\"text\":\"기간이 지난 뒤\"}").allowed()).isFalse();
    }


    @Test
    @DisplayName("함께 승인한 실행이 실패하거나 결과를 모르면 그 대화 허락을 거두어 다음 호출은 다시 승인을 기다린다")
    void failedOrUnknownExecutionRevokesConversationGrant() {
        when(connector.execute(anyString(), anyString(), anyString(), anyString()))
            .thenReturn(CallResult.failure(ConnectorCallError.FORBIDDEN))
            .thenThrow(new ConnectorExecutionUnknown());

        assertThat(service.approve(me, ask(root, WRITE, ARGS).actionId(), null, true).status())
            .isEqualTo(ActionStatus.FAILED);
        assertThat(ask(root, WRITE, "{\"text\":\"실패한 뒤\"}").allowed()).isFalse();
        assertThat(service.approve(me, ask(root, DRAFT, ARGS).actionId(), null, true).status())
            .isEqualTo(ActionStatus.UNKNOWN);
        assertThat(ask(root, DRAFT, "{\"text\":\"결과를 모른 뒤\"}").allowed()).isFalse();

        assertThat(conversationGrants.findAll()).hasSize(2)
            .allSatisfy(grant -> assertThat(grant.revokedAt()).isNotNull());
        assertThat(service.conversationGrants(me, conversation.id())).isEmpty();
    }


    @Test
    @DisplayName("결과를 모르는 쓰기를 같은 재시도 식별자로 다시 부르면 다른 줄이 준 대화 허락이 있어도 새 승인을 기다린다")
    void resendWithRetryIdentifierIgnoresConversationGrant() {
        String attemptId = "0b7f3c1e-5d2a-4c8e-9f61-2a3b4c5d6e7f";
        ConnectorManifest declaring = new ConnectorManifest(
            DECLARING.id(),
            DECLARING.title(),
            DECLARING.description(),
            DECLARING.fields(),
            DECLARING.verifyTool(),
            DECLARING.mcpServer(),
            DECLARING.schema(),
            DECLARING.tools(),
            DECLARING.skills(),
            DECLARING.appearance(),
            DECLARING.singleBinding(),
            DECLARING.ownerBrowser(),
            DECLARING.ownerBrowserLoginUrl(),
            DECLARING.executionGuard(),
            DECLARING.executionGuardManifestSha256(),
            DECLARING.snapshot(),
            new ConnectorUnknownErrors(Map.of("CAREER_OUTCOME_UNKNOWN", "attemptId")));
        when(connector.readCatalog()).thenReturn(List.of(declaring));
        ConnectorCatalogTimes.expire();
        when(connector.execute(anyString(), anyString(), anyString(), anyString()))
            .thenThrow(new ConnectorExecutionUnknown(new ConnectorUnknownDetail("CAREER_OUTCOME_UNKNOWN", "attemptId", attemptId)))
            .thenReturn(CallResult.success(JSON.readTree("{\"saved\":true}")));

        assertThat(service.approve(me, ask(root, WRITE, ARGS).actionId(), null).status()).isEqualTo(ActionStatus.UNKNOWN);
        assertThat(service.approve(me, ask(root, WRITE, "{\"text\":\"다른 글\"}").actionId(), null, true).status())
            .isEqualTo(ActionStatus.SUCCEEDED);

        ConnectorPolicyAnswer resend = ask(root, WRITE, "{\"text\":\"안녕\",\"attemptId\":\"" + attemptId + "\"}");
        assertThat(resend.allowed()).isFalse();
        assertThat(resend.actionId()).isNotNull();
        // 식별자를 싣지 않은 호출에는 대화 허락이 그대로 통한다.
        assertThat(ask(root, WRITE, "{\"text\":\"세 번째 글\"}").allowed()).isTrue();
        assertThat(service.conversationGrants(me, conversation.id())).hasSize(1);
    }


    @Test
    @DisplayName("선언이 대화 허락을 닫으면 정리가 유효한 허락을 거두고, 다시 열어도 앞선 허락은 돌아오지 않는다")
    void closedDeclarationRevokesConversationGrant() {
        service.approve(me, ask(root, WRITE, ARGS).actionId(), null, true);
        ConnectorManifest closed = new ConnectorManifest(
            DECLARING.id(),
            DECLARING.title(),
            DECLARING.description(),
            DECLARING.fields(),
            DECLARING.verifyTool(),
            DECLARING.mcpServer(),
            DECLARING.schema(),
            DECLARING.tools().stream()
                .map(tool -> WRITE.equals(tool.name())
                    ? new ConnectorTool(WRITE, "WRITE", "required", "메모 쓰기", true, List.of(), null, Map.of(), false)
                    : tool)
                .toList());
        when(connector.readCatalog()).thenReturn(List.of(closed));
        ConnectorCatalogTimes.expire();

        assertThat(service.revokeClosedConversationGrants(Instant.now())).isEqualTo(1);
        assertThat(service.revokeClosedConversationGrants(Instant.now())).as("거둔 줄은 다시 세지 않는다").isZero();

        when(connector.readCatalog()).thenReturn(List.of(DECLARING));
        ConnectorCatalogTimes.expire();
        assertThat(ask(root, WRITE, "{\"text\":\"다시 연 뒤\"}").allowed()).isFalse();
    }


    @Test
    @DisplayName("기다리던 카드 둘을 모두 대화 허락과 함께 승인해도 유효한 허락은 한 줄이고, 다시 묻기 한 번으로 모두 거둔다")
    void sameToolKeepsOneActiveConversationGrant() {
        UUID first = ask(root, WRITE, ARGS).actionId();
        UUID second = ask(root, WRITE, "{\"text\":\"두 번째\"}").actionId();
        service.approve(me, first, null, true);
        service.approve(me, second, null, true);

        assertThat(conversationGrants.findAll()).hasSize(2);
        assertThat(service.conversationGrants(me, conversation.id())).hasSize(1);
        // 거둔 줄과 같은 도구의 다른 줄이 남아 있으면 그 줄로 계속 통과한다. 남은 줄까지 거두는지 본다.
        jdbc.update("UPDATE connector_conversation_grant SET revoked_at = NULL");
        Long grantId = service.conversationGrants(me, conversation.id()).getFirst().grantId();

        service.revokeConversationGrant(me, conversation.id(), grantId);

        assertThat(conversationGrants.findAll()).allSatisfy(grant -> assertThat(grant.revokedAt()).isNotNull());
        assertThat(ask(root, WRITE, "{\"text\":\"거둔 뒤\"}").allowed()).isFalse();
    }


    @Test
    @DisplayName("에이전트에서 연결을 떼면 그 에이전트의 대화 허락을 거두고 다시 붙여도 돌아오지 않는다")
    void unbindRevokesConversationGrantOfThatAgent() {
        service.approve(me, ask(root, WRITE, ARGS).actionId(), null, true);

        bindingService.unbind(me, agent.code(), DEMO);
        bind(agent);

        assertThat(conversationGrants.findAll()).singleElement()
            .satisfies(grant -> assertThat(grant.revokedAt()).isNotNull());
        assertThat(ask(root, WRITE, "{\"text\":\"다시 붙인 뒤\"}").allowed()).isFalse();
    }


    @Test
    @DisplayName("연결을 해제하거나 값을 다시 등록하면 그 커넥터의 대화 허락을 모두 거둔다")
    void connectionChangeRevokesConversationGrants() {
        service.approve(me, ask(root, WRITE, ARGS).actionId(), null, true);

        new TransactionTemplate(transactionManager)
            .executeWithoutResult(status -> service.rejectPendingFor(connection, Instant.now()));

        assertThat(conversationGrants.findAll()).singleElement()
            .satisfies(grant -> assertThat(grant.revokedAt()).isNotNull());
        assertThat(ask(root, WRITE, "{\"text\":\"다시 등록한 뒤\"}").allowed()).isFalse();
    }


    @Test
    @DisplayName("같은 줄을 동시에 대화 허락과 함께 승인하면 한 번만 실행하고 허락도 한 줄만 남는다")
    void concurrentConversationGrantApprovalsExecuteOnceAndStoreOneGrant() throws Exception {
        UUID actionId = ask(root, WRITE, ARGS).actionId();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<String>> outcomes = new ArrayList<>();
        try {
            for (int i = 0; i < 2; i++) {
                Callable<String> approve = () -> {
                    start.await(10, TimeUnit.SECONDS);
                    try {
                        return service.approve(me, actionId, null, true).status().name();
                    } catch (ApiException ex) {
                        return ex.code().name();
                    }
                };
                outcomes.add(pool.submit(approve));
            }
            start.countDown();
            List<String> results = new ArrayList<>();
            for (Future<String> outcome : outcomes) {
                results.add(outcome.get(20, TimeUnit.SECONDS));
            }
            assertThat(results).containsExactlyInAnyOrder("SUCCEEDED", ErrorCode.CONNECTOR_ACTION_NOT_PENDING.name());
        } finally {
            pool.shutdownNow();
        }
        verify(connector, times(1)).execute(anyString(), anyString(), anyString(), anyString());
        assertThat(conversationGrants.findAll()).hasSize(1);
    }


    /** 주인 에이전트의 실행에서 부른 새 호출 하나의 판정을 묻는다. */
    private ConnectorPolicyAnswer ask(String session, String tool, String args) {
        return policies.decide(PROFILE, session, session, "call_" + UUID.randomUUID(), "mcp__demo__" + tool, tool, args);
    }


    private ConnectorActionView listed(UUID actionId) {
        return service.listForConversation(me, conversation.id()).stream()
            .filter(view -> view.actionId().equals(actionId))
            .findFirst()
            .orElseThrow();
    }


    private Agent agent(String profile) {
        return Agent.of(
            "grant-" + UUID.randomUUID(),
            "검사용 메모",
            profile,
            "http://localhost",
            CostMode.SUBSCRIPTION,
            CredentialScope.SHARED_HOUSEHOLD,
            AgentVisibility.PRIVATE,
            owner.id());
    }


    private ConnectorBinding bind(Agent target) {
        Instant now = Instant.parse("2026-10-01T00:00:00Z");
        ConnectorBinding binding = ConnectorBinding.pending(target, connection, "demo", now);
        binding.ready(now);
        return bindings.save(binding);
    }


    private Conversation startConversation() {
        return conversations.save(Conversation.startedBy(owner.id(), "대화 허락", agent.id(), Instant.now()));
    }


    /** 그 대화에서 도는 실행 하나를 만들고 루트 session 을 돌려준다. {@code parent} 가 있으면 그 실행의 위임 자식이다. */
    private String startRun(Agent runner, String profile, Long conversationId, AgentExecution parent) {
        String session = "fos-" + UUID.randomUUID();
        var run = AgentExecution.builder()
            .userId(owner.id())
            .agentId(runner.id())
            .conversationId(conversationId)
            .profileName(profile)
            .hermesSessionId(session)
            .costMode(CostMode.SUBSCRIPTION)
            .status(ExecutionStatus.RUNNING)
            .startedAt(Instant.parse("2026-10-01T00:00:00Z"));
        if (parent != null) {
            run.parentExecutionId(parent.id()).rootExecutionId(parent.id()).delegationKey("child-" + UUID.randomUUID());
        }
        executions.save(run.build());
        return session;
    }


    private static void assertCode(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call)
            .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo(expected));
    }


    private static CurrentUser currentUser(AppUser user) {
        return new CurrentUser(user.id(), user.email(), user.displayName(), user.groupId(), user.role());
    }


    /** 이 검사가 쓰는 표를 비운다. 컨텍스트를 함께 쓰는 다음 검사에 행을 남기지 않게 검사 뒤에도 부른다. */
    private void deleteRows() {
        jdbc.update("DELETE FROM decision_feedback_event");
        jdbc.update("DELETE FROM connector_conversation_grant");
        jdbc.update("DELETE FROM connector_action");
        jdbc.update("DELETE FROM connector_tool_grant");
        bindings.deleteAll();
        connections.deleteAll();
        McpCallSigner.clearRuns(jdbc, List.of(PROFILE, OTHER_PROFILE));
        agents.deleteAll();
        tokens.deleteAll();
        users.deleteAll();
    }
}
