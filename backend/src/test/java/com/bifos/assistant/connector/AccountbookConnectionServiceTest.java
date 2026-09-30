package com.bifos.assistant.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.connector.application.AccountbookConnectionService;
import com.bifos.assistant.connector.application.AccountbookTokenVerifier;
import com.bifos.assistant.connector.application.ConnectionSnapshot;
import com.bifos.assistant.connector.application.ConnectorOperationFailure;
import com.bifos.assistant.connector.domain.ConnectionStatus;
import com.bifos.assistant.connector.infra.AccountbookConnectionRepository;
import com.bifos.assistant.hermes.HermesConnectorClient;
import com.bifos.assistant.hermes.HermesDashboardClient;
import com.bifos.assistant.hermes.HermesToolsetClient;
import com.bifos.assistant.mcp.infra.AgentTokenRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.domain.UserRole;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("test")
class AccountbookConnectionServiceTest {
    private static final String TOKEN = "fab_" + "a".repeat(43);

    @Autowired AccountbookConnectionService service;
    @Autowired AccountbookConnectionRepository connections;
    @Autowired AgentRepository agents;
    @Autowired AgentTokenRepository tokens;
    @Autowired AppUserRepository users;
    @Autowired JdbcTemplate jdbc;

    @MockitoBean AccountbookTokenVerifier verifier;
    @MockitoBean HermesConnectorClient connector;
    @MockitoBean HermesToolsetClient toolsets;
    @MockitoBean HermesDashboardClient dashboard;

    @BeforeEach
    void 준비한다() {
        when(toolsets.readEnabled(anyString(), anyString())).thenReturn(List.of());
    }

    @AfterEach
    void 만든_행을_치운다() {
        connections.deleteAll();
        agents.deleteAll();
        tokens.deleteAll();
        users.deleteAll();
    }

    @Test
    void 외부_등록이_실패해도_새_트랜잭션에서_PENDING_비활성_상태가_남는다() {
        CurrentUser user = user(UserRole.MEMBER, 1L);
        when(connector.putEnv(anyString(), eq("ACCOUNTBOOK_API_TOKEN"), anyString())).thenReturn(true);
        doThrow(new IllegalStateException("dashboard unavailable"))
                .when(connector).putEnv(anyString(), eq("ACCOUNTBOOK_API_BASE_URL"), anyString());

        assertThatThrownBy(() -> service.register(user, TOKEN, null))
                .isInstanceOf(ConnectorOperationFailure.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.CONNECTOR_OPERATION_FAILED);

        var stored = connections.findById(user.id()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(ConnectionStatus.PENDING);
        assertThat(stored.isDesiredEnabled()).isFalse();
        assertThat(stored.isRestartRequired()).isTrue();
        assertThat(stored.getTokenPrefix()).isNull();
        assertThat(jdbc.queryForObject("SELECT enabled FROM agent WHERE id = (SELECT agent_id FROM accountbook_connection WHERE user_id = ?)", Boolean.class, user.id()))
                .isFalse();
    }

    @Test
    void profile_생성_실패는_연결_실패로_바꾸지_않고_행을_되돌린다() {
        CurrentUser user = user(UserRole.MEMBER, 1L);
        doThrow(new ApiException(ErrorCode.HERMES_UNAVAILABLE, "profile create failed"))
                .when(dashboard).createProfile(anyString());

        assertThatThrownBy(() -> service.register(user, TOKEN, null))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.HERMES_UNAVAILABLE));

        assertThat(connections.findById(user.id())).isEmpty();
        assertThat(agents.count()).isZero();
    }

    @Test
    void 두_사용자는_자기_바인딩으로만_연결을_읽는다() {
        CurrentUser first = user(UserRole.MEMBER, 1L);
        CurrentUser second = user(UserRole.MEMBER, 1L);

        UUID sameFamily = UUID.randomUUID();
        ConnectionSnapshot firstResult = service.register(first, TOKEN, sameFamily.toString());
        ConnectionSnapshot secondResult = service.register(second, "fab_" + "b".repeat(43), sameFamily.toString());

        assertThat(service.read(first).agentCode()).isEqualTo(firstResult.agentCode());
        assertThat(service.read(first).agentCode()).isNotEqualTo(secondResult.agentCode());
        assertThat(firstResult.familyUuid()).isEqualTo(secondResult.familyUuid()).isEqualTo(sameFamily);
        assertThat(firstResult.tokenPrefix()).isNotEqualTo(secondResult.tokenPrefix());
        org.mockito.Mockito.verify(verifier).verify(TOKEN, sameFamily);
        org.mockito.Mockito.verify(verifier).verify("fab_" + "b".repeat(43), sameFamily);
    }

    @Test
    void 재시작_대기_연결을_확인해도_READY가_되지_않는다() {
        CurrentUser user = user(UserRole.MEMBER, 1L);
        when(connector.putEnv(anyString(), anyString(), anyString())).thenReturn(true);
        service.register(user, TOKEN, null);
        when(connector.readConnector(anyString())).thenReturn(new HermesConnectorClient.ConnectorState("p", true, true, false));

        ConnectionSnapshot checked = service.check(user);

        assertThat(checked.status()).isEqualTo(ConnectionStatus.PENDING);
        assertThat(checked.restartRequired()).isTrue();
    }

    @Test
    void 해제_중_외부_실패는_비활성_PENDING_상태를_남긴다() {
        CurrentUser user = user(UserRole.MEMBER, 1L);
        service.register(user, TOKEN, null);
        reset(connector);
        when(connector.deleteEnv(anyString(), eq("ACCOUNTBOOK_API_TOKEN"))).thenReturn(true);
        doThrow(new IllegalStateException("delete failed")).when(connector).deleteEnv(anyString(), eq("ACCOUNTBOOK_FAMILY_UUID"));

        assertThatThrownBy(() -> service.disconnect(user)).isInstanceOf(ConnectorOperationFailure.class);

        var stored = connections.findById(user.id()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(ConnectionStatus.PENDING);
        assertThat(stored.isDesiredEnabled()).isFalse();
        assertThat(stored.isRestartRequired()).isTrue();
        assertThat(jdbc.queryForObject("SELECT enabled FROM agent WHERE id = (SELECT agent_id FROM accountbook_connection WHERE user_id = ?)", Boolean.class, user.id()))
                .isFalse();
    }

    @Test
    void 활성화_후보가_아닌_연결은_관리자_확인으로_READY가_되지_않는다() {
        CurrentUser member = user(UserRole.MEMBER, 1L);
        CurrentUser admin = user(UserRole.ADMIN, 1L);
        service.register(member, TOKEN, null);
        reset(connector);
        doThrow(new IllegalStateException("delete failed")).when(connector).deleteEnv(anyString(), anyString());
        assertThatThrownBy(() -> service.disconnect(member)).isInstanceOf(ConnectorOperationFailure.class);
        reset(connector);
        when(connector.readConnector(anyString())).thenReturn(new HermesConnectorClient.ConnectorState("p", true, true, false));

        assertThatThrownBy(() -> service.confirmApplied(admin, member.id()))
                .isInstanceOf(ConnectorOperationFailure.class);

        assertThat(connections.findById(member.id()).orElseThrow().getStatus()).isEqualTo(ConnectionStatus.PENDING);
    }

    @Test
    void 같은_그룹_ADMIN은_반영_확인으로_READY로_바꾼다() {
        CurrentUser member = user(UserRole.MEMBER, 1L);
        CurrentUser admin = user(UserRole.ADMIN, 1L);
        service.register(member, TOKEN, null);
        when(connector.readConnector(anyString())).thenReturn(new HermesConnectorClient.ConnectorState("p", true, true, false));
        when(connector.probeAccountbook(anyString())).thenReturn(new HermesConnectorClient.ProbeResult(true, List.of("accountbook_search")));

        ConnectionSnapshot confirmed = service.confirmApplied(admin, member.id());

        assertThat(confirmed.status()).isEqualTo(ConnectionStatus.READY);
    }

    @Test
    void 해제된_연결은_관리자_반영_확인_뒤에도_DISCONNECTED다() {
        CurrentUser member = user(UserRole.MEMBER, 1L);
        CurrentUser admin = user(UserRole.ADMIN, 1L);
        service.register(member, TOKEN, null);
        when(connector.deleteEnv(anyString(), anyString())).thenReturn(true);
        service.disconnect(member);
        when(connector.readConnector(anyString())).thenReturn(new HermesConnectorClient.ConnectorState("p", false, false, false));

        ConnectionSnapshot confirmed = service.confirmApplied(admin, member.id());

        assertThat(confirmed.status()).isEqualTo(ConnectionStatus.DISCONNECTED);
        assertThat(confirmed.restartRequired()).isFalse();
    }

    @Test
    void MEMBER는_연결_반영_완료를_확인할_수_없다() {
        CurrentUser member = user(UserRole.MEMBER, 1L);
        service.register(member, TOKEN, null);

        assertThatThrownBy(() -> service.confirmApplied(member, member.id()))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void 다른_그룹_ADMIN은_연결_반영을_확인할_수_없다() {
        CurrentUser member = user(UserRole.MEMBER, 1L);
        CurrentUser anotherGroupAdmin = user(UserRole.ADMIN, 2L);
        service.register(member, TOKEN, null);

        assertThatThrownBy(() -> service.confirmApplied(anotherGroupAdmin, member.id()))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    private CurrentUser user(UserRole role, long groupId) {
        String suffix = UUID.randomUUID().toString();
        AppUser saved = users.save(AppUser.of("connector-" + suffix + "@example.com", suffix, groupId, role));
        return new CurrentUser(saved.id(), saved.email(), saved.displayName(), saved.groupId(), saved.role());
    }
}
