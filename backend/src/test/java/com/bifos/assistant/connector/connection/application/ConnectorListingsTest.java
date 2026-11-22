package com.bifos.assistant.connector.connection.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.connector.connection.application.dto.BoundAgentSummary;
import com.bifos.assistant.connector.connection.application.dto.ConnectorSummary;
import com.bifos.assistant.connector.connection.domain.type.BindingStatus;
import com.bifos.assistant.connector.connection.domain.type.ConnectionStatus;
import com.bifos.assistant.hermes.application.dto.ConnectorAppearance;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@code connector_list} 의 항목 모양과 붙은 상태, 링크의 {@code ?agent=} 규칙을 확인한다(스펙 service-concepts REQ5, DECISION22). */
class ConnectorListingsTest {

    private static final CurrentUser OWNER = new CurrentUser(1L, "owner@example.com", "owner", 10L, UserRole.MEMBER);
    private static final Long AGENT_ID = 7L;
    private static final String CODE = "my-agent";

    private final ConnectorConnectionService connections = mock(ConnectorConnectionService.class);
    private final AgentService agents = mock(AgentService.class);
    private final ConnectorListings listings = new ConnectorListings(connections, agents);


    @Test
    @DisplayName("요청자가 주인인 비공개 에이전트는 링크에 agent 를 붙이고 붙은 상태를 셋으로 나누며 쓸 수 없는 항목을 뺀다")
    void ownPrivateAgentGetsAgentLinkAndBindingStates() {
        agent(AgentVisibility.PRIVATE, OWNER.id());
        when(connections.catalog(OWNER)).thenReturn(List.of(
            summary("calendar", ConnectionStatus.READY, true, List.of(bound(CODE, BindingStatus.READY, false))),
            summary("mail", ConnectionStatus.READY, true, List.of(bound(CODE, BindingStatus.READY, true))),
            summary("blog", ConnectionStatus.PENDING, true, List.of(bound("other", BindingStatus.READY, false))),
            summary("gone", ConnectionStatus.READY, false, List.of())));

        List<Map<String, Object>> items = listings.list(OWNER, AGENT_ID);

        assertThat(items).extracting(item -> item.get("id")).containsExactly("calendar", "mail", "blog");
        assertThat(items.get(0)).containsEntry("connected", true).containsEntry("thisAgent", "ready")
            .containsEntry("link", "/connections/calendar?agent=" + CODE).containsOnlyKeys("id", "title", "connected", "thisAgent", "link");
        assertThat(items.get(1)).containsEntry("thisAgent", "pending");
        assertThat(items.get(2)).containsEntry("connected", false).containsEntry("thisAgent", "none");
    }


    @Test
    @DisplayName("그룹 공개 에이전트와 남의 에이전트는 agent 없는 링크를 받는다")
    void groupAgentAndOthersAgentGetPlainLink() {
        agent(AgentVisibility.GROUP, OWNER.id());
        when(connections.catalog(OWNER)).thenReturn(List.of(summary("calendar", ConnectionStatus.DISCONNECTED, true, List.of())));
        assertThat(listings.list(OWNER, AGENT_ID).getFirst()).containsEntry("link", "/connections/calendar");

        agent(AgentVisibility.PRIVATE, 99L);
        assertThat(listings.list(OWNER, AGENT_ID).getFirst()).containsEntry("link", "/connections/calendar");
    }


    @Test
    @DisplayName("에이전트를 찾지 못하면 agent 없는 링크와 안 붙음 상태를 준다")
    void unknownAgentGetsPlainLinkAndNoBinding() {
        when(agents.findById(AGENT_ID)).thenReturn(Optional.empty());
        when(connections.catalog(OWNER)).thenReturn(List.of(
            summary("calendar", ConnectionStatus.READY, true, List.of(bound(CODE, BindingStatus.READY, false)))));

        assertThat(listings.list(OWNER, AGENT_ID).getFirst())
            .containsEntry("link", "/connections/calendar")
            .containsEntry("thisAgent", "none");
    }


    private void agent(AgentVisibility visibility, Long ownerUserId) {
        Agent agent = Agent.of(CODE, "내 에이전트", "profile", "http://hermes.invalid", CostMode.SUBSCRIPTION, CredentialScope.SHARED_HOUSEHOLD,
            visibility, ownerUserId);
        when(agents.findById(AGENT_ID)).thenReturn(Optional.of(agent));
    }


    private static ConnectorSummary summary(String id, ConnectionStatus status, boolean available, List<BoundAgentSummary> bindings) {
        return new ConnectorSummary(id, id + " 이름", "설명", ConnectorAppearance.NONE, List.of(), List.of(), status, available, bindings);
    }


    private static BoundAgentSummary bound(String code, BindingStatus status, boolean restartRequired) {
        return new BoundAgentSummary(code, code, status, restartRequired, false);
    }
}
