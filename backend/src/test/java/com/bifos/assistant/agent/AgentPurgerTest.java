package com.bifos.assistant.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.bifos.assistant.agent.application.AgentPurger;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.application.ConversationWriter;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.hermes.HermesDashboardClient;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 정리 작업이 지운 지 기한이 지난 에이전트를 고르고, profile 을 거둔 뒤 행을 지우는지 확인한다. 일정을 기다리지 않고 직접 부른다.
 *
 * <p>다른 검사가 남긴 지운 에이전트도 함께 정리되므로 지운 수를 정확히 단언하지 않고 에이전트마다 행이 남았는지 본다.
 */
@BackendIntegrationTest
class AgentPurgerTest {

    /** 이 시각과 지운 시각을 아주 옛날로 두어, 다른 검사가 실제 시각으로 지운 에이전트가 후보에 들지 않게 한다. */
    static final Instant NOW = Instant.parse("2000-01-20T00:00:00Z");

    @Autowired
    AgentPurger purger;

    @Autowired
    AgentRepository agents;

    @Autowired
    AppUserRepository users;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    ConversationWriter conversationWriter;

    @Autowired
    HermesDashboardClient dashboard;

    AppUser owner;

    @BeforeEach
    void setUp() {
        String email = "agent-purger-" + UUID.randomUUID() + "@example.test";
        owner = users.save(AppUser.of(email, email, 1L, UserRole.MEMBER, NOW));
    }

    @Test
    @DisplayName("지운 지 7일이 지난 관리형 에이전트는 profile 을 거두고 행을 지운다")
    void deprovisionsAndPurgesManagedAgentDeletedOverSevenDaysAgo() {
        Agent agent = createAgent(true, NOW.minus(Duration.ofDays(8)));

        int purged = purger.purgeDue(NOW);

        assertThat(purged).isGreaterThanOrEqualTo(1);
        assertThat(agents.findById(agent.id())).isEmpty();
        verify(dashboard).deleteProfile(agent.hermesProfile());
    }

    @Test
    @DisplayName("7일이 지나지 않은 에이전트는 남긴다")
    void keepsAgentDeletedWithinSevenDays() {
        Agent agent = createAgent(true, NOW.minus(Duration.ofDays(6)));

        purger.purgeDue(NOW);

        assertThat(agents.findById(agent.id())).isPresent();
        verify(dashboard, never()).deleteProfile(agent.hermesProfile());
    }

    @Test
    @DisplayName("지우지 않은 에이전트는 건드리지 않는다")
    void leavesActiveAgentUntouched() {
        Agent agent = createAgent(true, null);

        purger.purgeDue(NOW);

        assertThat(agents.findById(agent.id())).isPresent();
        verify(dashboard, never()).deleteProfile(agent.hermesProfile());
    }

    @Test
    @DisplayName("관리형이 아닌 에이전트는 Hermes 를 부르지 않고 지운다")
    void purgesUnmanagedAgentWithoutCallingHermes() {
        Agent agent = createAgent(false, NOW.minus(Duration.ofDays(8)));

        purger.purgeDue(NOW);

        assertThat(agents.findById(agent.id())).isEmpty();
        verify(dashboard, never()).deleteProfile(agent.hermesProfile());
    }

    @Test
    @DisplayName("지웠지만 정리되지 않은 대화가 있으면 Hermes 를 부르지 않고 미룬다")
    void defersAgentWithUnpurgedConversationWithoutCallingHermes() {
        Agent agent = createAgent(true, NOW.minus(Duration.ofDays(8)));
        Conversation conversation =
                conversations.saveAndFlush(Conversation.startedBy(owner.id(), "남은 대화", agent.id(), NOW));
        conversationWriter.deleteIfActive(conversation.id(), owner.id(), NOW);

        purger.purgeDue(NOW);

        assertThat(agents.findById(agent.id())).isPresent();
        verify(dashboard, never()).deleteProfile(agent.hermesProfile());
    }

    @Test
    @DisplayName("profile 거두기가 실패하면 행을 남기고 간격이 지난 뒤 다시 지운다")
    void keepsAgentWhenDeprovisionFailsAndRetriesAfterBackoff() {
        Agent agent = createAgent(true, NOW.minus(Duration.ofDays(8)));
        doThrow(new ApiException(ErrorCode.HERMES_UNAVAILABLE, "down"))
                .when(dashboard)
                .deleteProfile(agent.hermesProfile());

        purger.purgeDue(NOW);

        assertThat(agents.findById(agent.id())).as("거두기가 실패하면 행이 남는다").isPresent();

        purger.purgeDue(NOW.plusSeconds(30));

        verify(dashboard, times(1)).deleteProfile(agent.hermesProfile());
        assertThat(agents.findById(agent.id())).as("기다리는 간격 안에는 다시 보지 않는다").isPresent();

        doNothing().when(dashboard).deleteProfile(agent.hermesProfile());
        purger.purgeDue(NOW.plusSeconds(61));

        assertThat(agents.findById(agent.id())).as("간격이 지나면 다시 거두고 지운다").isEmpty();
        verify(dashboard, times(2)).deleteProfile(agent.hermesProfile());
    }

    /** profile 이름과 code 는 Hermes profile 이름 규칙에 맞게 소문자와 숫자로 만든다. deletedAt 이 null 이면 지우지 않는다. */
    Agent createAgent(boolean managed, Instant deletedAt) {
        String code = "purge-" + UUID.randomUUID().toString().substring(0, 8);
        Agent agent = Agent.of(
                code,
                "정리할 에이전트",
                code,
                "http://agent-runtime.test/p/" + code,
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE,
                owner.id(),
                NOW.minus(Duration.ofDays(30)));
        if (managed) {
            agent.markManagedProfile();
        }
        if (deletedAt != null) {
            // 엔티티로 적어 읽는 쪽과 같은 UTC 로 저장한다. JdbcTemplate 으로 적으면 JVM 시간대가 섞인다.
            agent.markDeleted(deletedAt);
        }
        return agents.saveAndFlush(agent);
    }
}
