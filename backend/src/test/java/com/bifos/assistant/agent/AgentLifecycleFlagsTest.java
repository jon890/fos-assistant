package com.bifos.assistant.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.agent.application.AgentEndpointProbe;
import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentVisibility;
import com.bifos.assistant.agent.domain.CostMode;
import com.bifos.assistant.agent.domain.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.agent.presentation.AgentAdminController;
import com.bifos.assistant.agent.presentation.AgentDtos.AdminAgentView;
import com.bifos.assistant.agent.presentation.AgentDtos.CreateAgentRequest;
import com.bifos.assistant.agent.presentation.AgentDtos.UpdateAgentRequest;
import com.bifos.assistant.chat.application.ChatService;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ChatMessageRepository;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.hermes.HermesRunsClient;
import com.bifos.assistant.hermes.HermesToolsetClient;
import com.bifos.assistant.hermes.StubHermesRunsClient;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.domain.UserRole;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 지운 에이전트가 읽기 목록, 대화 시작, 이어 보내기, 쓰기 경로에서 없는 에이전트로 보이는지와, 그룹에
 * 공개해도 주인이 남는지를 실제 저장소로 본다(ADR-033).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(AgentLifecycleFlagsTest.StubRuntime.class)
class AgentLifecycleFlagsTest {

    @TestConfiguration
    static class StubRuntime {
        @Bean
        @Primary
        StubHermesRunsClient stubHermesRunsClient() {
            return new StubHermesRunsClient();
        }
    }

    private static final Instant DELETED_AT = Instant.parse("2026-09-29T00:00:00Z");

    @Autowired AgentService agentService;
    @Autowired AgentAdminController admin;
    @Autowired ChatService chat;
    @Autowired AgentRepository agents;
    @Autowired AppUserRepository users;
    @Autowired ConversationRepository conversations;
    @Autowired ChatMessageRepository messages;
    @Autowired HermesRunsClient hermes;
    @Autowired PlatformTransactionManager transactionManager;

    /** 그룹으로 공개하는 요청이 도구 목록을 읽는다. 실제 Hermes 를 부르지 않도록 빈 목록을 돌려준다. */
    @MockitoBean HermesToolsetClient hermesToolsets;

    /** 관리자가 등록할 때 주소가 닿는지 본다. 실제 Hermes 를 부르지 않도록 통과시킨다. */
    @MockitoBean AgentEndpointProbe endpointProbe;

    private CurrentUser owner;
    private CurrentUser otherMember;
    private CurrentUser administrator;

    @BeforeEach
    void reset() {
        ((StubHermesRunsClient) hermes).reset();
        messages.deleteAll();
        conversations.deleteAll();
        agents.deleteAll();
        users.deleteAll();
        owner = user("owner@example.com", UserRole.MEMBER);
        otherMember = user("other@example.com", UserRole.MEMBER);
        administrator = user("admin@example.com", UserRole.ADMIN);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 지운_에이전트는_목록과_시작과_쓰기에서_없는_에이전트이고_번호로는_읽힌다() {
        Agent agent = agents.save(privateAgentOf("helper", owner));
        agent.markDeleted(DELETED_AT);
        agents.save(agent);

        assertThat(agentService.readableBy(owner))
                .extracting(Agent::code)
                .doesNotContain("helper");
        assertNotFound(() -> agentService.requireStartable(owner, "helper"));
        assertNotFound(() -> new TransactionTemplate(transactionManager)
                .execute(status -> agentService.requireReadableForUpdate(owner, "helper")));

        Agent byId = agentService.requireById(agent.id());
        assertThat(byId.isDeleted()).isTrue();
        assertThat(byId.enabled()).as("지우면 꺼진다").isFalse();
        assertThat(byId.deletedAt()).isEqualTo(DELETED_AT);
    }

    @Test
    void 지운_에이전트의_기존_대화에_보내면_없는_에이전트이고_Hermes_를_부르지_않는다() {
        agents.save(privateAgentOf("helper", owner));
        Conversation conversation = chat.startEmpty(owner, "helper");
        Agent agent = agents.findByCode("helper").orElseThrow();
        agent.markDeleted(DELETED_AT);
        agents.save(agent);

        assertNotFound(() -> chat.send(owner, conversation.id(), "아직 있니?", null));
        assertThat(((StubHermesRunsClient) hermes).received()).isEmpty();
    }

    @Test
    void 관리자도_지운_에이전트를_켜지_못하고_관리_목록에서_보지_못한다() {
        Agent deleted = agents.save(privateAgentOf("helper", owner));
        deleted.markDeleted(DELETED_AT);
        agents.save(deleted);
        agents.save(privateAgentOf("kept", owner));
        signIn(administrator);

        assertNotFound(() -> admin.update(
                "helper", new UpdateAgentRequest(true, AgentVisibility.PRIVATE, null, null)));
        assertThat(agents.findByCode("helper").orElseThrow().enabled()).isFalse();
        assertThat(admin.list()).extracting(AdminAgentView::code).containsExactly("kept");
    }

    @Test
    void 그룹으로_공개해도_주인이_남고_주인만_고친다() {
        agents.save(privateAgentOf("helper", owner));
        signIn(administrator);

        AdminAgentView view = admin.update(
                "helper", new UpdateAgentRequest(true, AgentVisibility.GROUP, null, null));

        assertThat(view.visibility()).isEqualTo("GROUP");
        assertThat(view.ownerUserId()).isEqualTo(owner.id());
        Agent saved = agents.findByCode("helper").orElseThrow();
        assertThat(saved.ownerUserId()).isEqualTo(owner.id());
        assertThat(agentService.isEditableBy(owner, saved)).isTrue();
        assertThat(agentService.isEditableBy(otherMember, saved)).isFalse();
        assertThat(agentService.isEditableBy(administrator, saved)).isTrue();
    }

    @Test
    void 관리자가_그룹_에이전트를_만들_때_준_사용자가_주인이_되고_없는_사용자는_거절한다() {
        signIn(administrator);

        AdminAgentView owned = admin.create(groupRequest("family", owner.email()));
        AdminAgentView ownerless = admin.create(groupRequest("shared", null));

        assertThat(owned.ownerUserId()).isEqualTo(owner.id());
        assertThat(ownerless.ownerUserId()).isNull();
        assertThatThrownBy(() -> admin.create(groupRequest("ghost", "ghost@example.com")))
                .isInstanceOf(ApiException.class)
                .extracting(thrown -> ((ApiException) thrown).code())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(agents.findByCode("ghost")).isEmpty();
    }

    private static CreateAgentRequest groupRequest(String code, String ownerEmail) {
        return new CreateAgentRequest(code, code, code, "http://agent-runtime.test/p/" + code,
                CostMode.SUBSCRIPTION, CredentialScope.SHARED_HOUSEHOLD, AgentVisibility.GROUP,
                ownerEmail, null);
    }

    private void assertNotFound(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(ApiException.class)
                .extracting(thrown -> ((ApiException) thrown).code())
                .isEqualTo(ErrorCode.AGENT_NOT_FOUND);
    }

    private CurrentUser user(String email, UserRole role) {
        AppUser saved = users.save(AppUser.of(email, email, 1L, role));
        return new CurrentUser(saved.id(), saved.email(), saved.displayName(), saved.groupId(), saved.role());
    }

    private static Agent privateAgentOf(String code, CurrentUser owner) {
        return Agent.of(code, code, code, "http://agent-runtime.test/p/" + code,
                CostMode.SUBSCRIPTION, CredentialScope.SHARED_HOUSEHOLD, AgentVisibility.PRIVATE, owner.id());
    }

    private static void signIn(CurrentUser user) {
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(user, null));
    }
}
