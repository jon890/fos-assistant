package com.bifos.assistant.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

import com.bifos.assistant.agent.application.AgentEndpointProbe;
import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.agent.presentation.AgentAdminController;
import com.bifos.assistant.agent.presentation.AgentDtos.AdminAgentView;
import com.bifos.assistant.agent.presentation.AgentDtos.CreateAgentRequest;
import com.bifos.assistant.agent.presentation.AgentDtos.UpdateAgentRequest;
import com.bifos.assistant.agent.presentation.AgentDtos.UpdateToolsetsRequest;
import com.bifos.assistant.agent.presentation.AgentToolController;
import com.bifos.assistant.chat.application.ChatService;
import com.bifos.assistant.chat.domain.ChatMessage;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ChatMessageRepository;
import com.bifos.assistant.hermes.HermesRunsClient;
import com.bifos.assistant.hermes.HermesToolsetClient;
import com.bifos.assistant.hermes.StubHermesRunsClient;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
 *
 * <p>표를 비우지 않는다. 번호와 메일을 무작위로 만들어 이 클래스가 만든 행만 읽고 단언한다. 같은 스프링
 * 문맥을 쓰는 다른 테스트의 행을 지우지 않고, 그 행에 단언이 흔들리지도 않는다.
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

    @Autowired
    AgentService agentService;

    @Autowired
    AgentAdminController admin;

    @Autowired
    AgentToolController agentTools;

    @Autowired
    ChatService chat;

    @Autowired
    AgentRepository agents;

    @Autowired
    AppUserRepository users;

    @Autowired
    ChatMessageRepository messages;

    @Autowired
    HermesRunsClient hermes;

    @Autowired
    PlatformTransactionManager transactionManager;

    /** 그룹으로 공개하는 요청이 도구 목록을 읽는다. 실제 Hermes 를 부르지 않도록 빈 목록을 돌려준다. */
    @MockitoBean
    HermesToolsetClient hermesToolsets;

    /** 관리자가 등록할 때 주소가 닿는지 본다. 실제 Hermes 를 부르지 않도록 통과시킨다. */
    @MockitoBean
    AgentEndpointProbe endpointProbe;

    private CurrentUser owner;
    private CurrentUser otherMember;
    private CurrentUser administrator;

    @BeforeEach
    void reset() {
        ((StubHermesRunsClient) hermes).reset();
        owner = user(UserRole.MEMBER);
        otherMember = user(UserRole.MEMBER);
        administrator = user(UserRole.ADMIN);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("지운 에이전트는 목록과 시작과 쓰기에서 없는 에이전트이고 번호로는 읽힌다")
    void deletedAgentIsMissingInListStartAndWriteButReadableById() {
        String helper = randomCode("helper");
        Agent agent = agents.save(privateAgentOf(helper, owner));
        agent.markDeleted(DELETED_AT);
        agents.save(agent);

        assertThat(agentService.readableBy(owner)).extracting(Agent::code).doesNotContain(helper);
        assertNotFound(() -> agentService.requireStartable(owner, helper));
        assertNotFound(() -> new TransactionTemplate(transactionManager)
                .execute(status -> agentService.requireReadableForUpdate(owner, helper)));

        Agent byId = agentService.requireById(agent.id());
        assertThat(byId.isDeleted()).isTrue();
        assertThat(byId.enabled()).as("지우면 꺼진다").isFalse();
        assertThat(byId.deletedAt()).isEqualTo(DELETED_AT);
    }

    @Test
    @DisplayName("지운 에이전트의 기존 대화에 보내면 없는 에이전트이고 Hermes 를 부르지 않는다")
    void sendingToConversationOfDeletedAgentIsMissingAndSkipsHermes() {
        String helper = randomCode("helper");
        agents.save(privateAgentOf(helper, owner));
        Conversation conversation = chat.startEmpty(owner, helper);
        Agent agent = agents.findByCode(helper).orElseThrow();
        agent.markDeleted(DELETED_AT);
        agents.save(agent);

        assertNotFound(() -> chat.send(owner, conversation.id(), "아직 있니?", null));
        assertThat(((StubHermesRunsClient) hermes).received()).isEmpty();
    }

    /** 다시 만들기는 새 질문 없이 기존 대화의 에이전트로 곧바로 간다. 그 길도 지운 에이전트를 막는다. */
    @Test
    @DisplayName("지운 에이전트의 답을 다시 만들면 없는 에이전트이고 Hermes 를 부르지 않는다")
    void regeneratingReplyOfDeletedAgentIsMissingAndSkipsHermes() {
        String helper = randomCode("helper");
        agents.save(privateAgentOf(helper, owner));
        Conversation conversation = chat.startEmpty(owner, helper);
        messages.save(ChatMessage.fromUser(conversation.id(), owner.id(), "오늘 숙제가 뭐였지?", Instant.now()));
        Agent agent = agents.findByCode(helper).orElseThrow();
        agent.markDeleted(DELETED_AT);
        agents.save(agent);

        assertNotFound(() -> chat.regenerate(owner, conversation.id(), event -> {}));
        assertThat(((StubHermesRunsClient) hermes).received()).isEmpty();
    }

    @Test
    @DisplayName("관리자도 지운 에이전트를 켜지 못하고 관리 목록에서 보지 못한다")
    void adminCannotEnableDeletedAgentNorSeeItInManageList() {
        String helper = randomCode("helper");
        String kept = randomCode("kept");
        Agent deleted = agents.save(privateAgentOf(helper, owner));
        deleted.markDeleted(DELETED_AT);
        agents.save(deleted);
        agents.save(privateAgentOf(kept, owner));
        signIn(administrator);

        assertNotFound(() -> admin.update(helper, new UpdateAgentRequest(true, AgentVisibility.PRIVATE, null, null)));
        assertThat(agents.findByCode(helper).orElseThrow().enabled()).isFalse();
        assertThat(admin.list()).extracting(AdminAgentView::code).contains(kept).doesNotContain(helper);
    }

    @Test
    @DisplayName("관리자도 지운 에이전트의 도구를 읽거나 바꾸지 못한다")
    void adminCannotReadOrChangeToolsOfDeletedAgent() {
        String helper = randomCode("helper");
        Agent deleted = agents.save(privateAgentOf(helper, owner));
        deleted.markDeleted(DELETED_AT);
        agents.save(deleted);
        signIn(administrator);

        assertNotFound(() -> agentTools.readAdmin(helper));
        assertNotFound(() -> agentTools.writeAdmin(helper, new UpdateToolsetsRequest(List.of("web"))));
        verifyNoInteractions(hermesToolsets);
    }

    @Test
    @DisplayName("그룹으로 공개해도 주인이 남고 주인만 고친다")
    void keepsOwnerWhenPublishedToGroupAndOnlyOwnerEdits() {
        String helper = randomCode("helper");
        agents.save(privateAgentOf(helper, owner));
        signIn(administrator);

        AdminAgentView view = admin.update(helper, new UpdateAgentRequest(true, AgentVisibility.GROUP, null, null));

        assertThat(view.visibility()).isEqualTo("GROUP");
        assertThat(view.ownerUserId()).isEqualTo(owner.id());
        Agent saved = agents.findByCode(helper).orElseThrow();
        assertThat(saved.ownerUserId()).isEqualTo(owner.id());
        assertThat(agentService.isEditableBy(owner, saved)).isTrue();
        assertThat(agentService.isEditableBy(otherMember, saved)).isFalse();
        assertThat(agentService.isEditableBy(administrator, saved)).isTrue();
    }

    @Test
    @DisplayName("관리자가 그룹 에이전트를 만들 때 준 사용자가 주인이 되고 없는 사용자는 거절한다")
    void adminCreatedGroupAgentGetsGivenUserAsOwnerAndRejectsUnknownUser() {
        signIn(administrator);

        String ghost = randomCode("ghost");

        AdminAgentView owned = admin.create(groupRequest(randomCode("family"), owner.email()));
        AdminAgentView ownerless = admin.create(groupRequest(randomCode("shared"), null));

        assertThat(owned.ownerUserId()).isEqualTo(owner.id());
        assertThat(ownerless.ownerUserId()).isNull();
        assertThatThrownBy(() -> admin.create(groupRequest(ghost, randomEmail())))
                .isInstanceOf(ApiException.class)
                .extracting(thrown -> ((ApiException) thrown).code())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(agents.findByCode(ghost)).isEmpty();
    }

    private static CreateAgentRequest groupRequest(String code, String ownerEmail) {
        return new CreateAgentRequest(
                code,
                code,
                code,
                "http://agent-runtime.test/p/" + code,
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.GROUP,
                ownerEmail,
                null);
    }

    private void assertNotFound(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(ApiException.class)
                .extracting(thrown -> ((ApiException) thrown).code())
                .isEqualTo(ErrorCode.AGENT_NOT_FOUND);
    }

    private CurrentUser user(UserRole role) {
        String email = randomEmail();
        AppUser saved = users.save(AppUser.of(email, email, 1L, role, Instant.now()));
        return new CurrentUser(saved.id(), saved.email(), saved.displayName(), saved.groupId(), saved.role());
    }

    /** 에이전트 번호 규칙에 맞는 소문자와 숫자, {@code -} 로 만든다. */
    private static String randomCode(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 13);
    }

    private static String randomEmail() {
        return "flags-" + UUID.randomUUID() + "@example.com";
    }

    private static Agent privateAgentOf(String code, CurrentUser owner) {
        return Agent.of(
                code,
                code,
                code,
                "http://agent-runtime.test/p/" + code,
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE,
                owner.id(),
                Instant.now());
    }

    private static void signIn(CurrentUser user) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null));
    }
}
