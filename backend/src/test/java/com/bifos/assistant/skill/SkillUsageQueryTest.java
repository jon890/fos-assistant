package com.bifos.assistant.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentVisibility;
import com.bifos.assistant.agent.domain.CostMode;
import com.bifos.assistant.agent.domain.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.hermes.HermesSkillClient;
import com.bifos.assistant.hermes.HermesSkillClient.HermesSkill;
import com.bifos.assistant.hermes.HermesToolsetClient;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.skill.application.SkillList;
import com.bifos.assistant.skill.application.SkillListItem;
import com.bifos.assistant.skill.application.SkillService;
import com.bifos.assistant.skill.application.SkillUsageQuery;
import com.bifos.assistant.skill.application.SkillUsageSummary;
import com.bifos.assistant.skill.application.UserSkillUsage;
import com.bifos.assistant.skill.domain.ExecutionSkillUse;
import com.bifos.assistant.skill.domain.SkillUseSource;
import com.bifos.assistant.skill.infra.ExecutionSkillUseRepository;
import com.bifos.assistant.usage.application.ExecutionTreeService;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.presentation.UsageController;
import com.bifos.assistant.usage.presentation.UsageDtos;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.domain.UserRole;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 스킬 호출 이력을 누가 어디까지 보는지 본다(ADR-034).
 *
 * <p>가족용 에이전트 하나를 두 사용자가 쓴다. 주인은 스킬 목록에서 둘을 합친 합계만 보고, 각 사용자는
 * {@code /usage/skills} 에서 자기 호출과 그 대화만 본다.
 */
@SpringBootTest
@ActiveProfiles("test")
class SkillUsageQueryTest {

    private static final String AGENT_CODE = "usage-family";
    private static final String AGENT_PROFILE = "usage-family-profile";

    private static final Instant T1 = Instant.parse("2026-09-20T01:00:00Z");
    private static final Instant T2 = Instant.parse("2026-09-21T01:00:00Z");
    private static final Instant T3 = Instant.parse("2026-09-22T01:00:00Z");

    @Autowired SkillUsageQuery query;
    @Autowired SkillService skills;
    @Autowired ExecutionSkillUseRepository uses;
    @Autowired AgentExecutionRepository executions;
    @Autowired ConversationRepository conversations;
    @Autowired AgentRepository agents;
    @Autowired AppUserRepository users;
    @Autowired AgentService agentService;
    @Autowired ExecutionTreeService trees;

    @MockitoBean HermesSkillClient skillClient;
    @MockitoBean HermesToolsetClient toolsets;

    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
    private UsageController controller;

    private CurrentUser dad;
    private CurrentUser kid;
    private Agent agent;
    private Conversation dadFirst;
    private Conversation dadSecond;
    private Conversation kidOnly;

    @BeforeEach
    void 준비한다() {
        uses.deleteAll();
        executions.deleteAll();
        agents.findByCode(AGENT_CODE).ifPresent(agents::delete);
        dad = user("usage-dad@example.com", "아빠");
        kid = user("usage-kid@example.com", "아이");
        agent = agents.save(Agent.of(AGENT_CODE, "가족 비서", AGENT_PROFILE, "http://agent-runtime.test/p/family",
                CostMode.SUBSCRIPTION, CredentialScope.SHARED_HOUSEHOLD, AgentVisibility.GROUP, dad.id()));
        dadFirst = conversations.save(Conversation.startedBy(dad.id(), "장보기 첫째", agent.id()));
        dadSecond = conversations.save(Conversation.startedBy(dad.id(), "장보기 둘째", agent.id()));
        kidOnly = conversations.save(Conversation.startedBy(kid.id(), "아이 장보기", agent.id()));
        // 아빠가 둘, 아이가 하나 읽었다. 마지막 호출은 아이의 것이다.
        use(execution(dad, dadFirst), "shopping", SkillUseSource.MODEL, T1);
        use(execution(dad, dadSecond), "shopping", SkillUseSource.COMMAND, T2);
        use(execution(kid, kidOnly), "shopping", SkillUseSource.MODEL, T3);
        controller = new UsageController(executions, currentUser, agentService, trees, conversations, query);
        when(toolsets.readEnabled(anyString(), anyString())).thenReturn(List.of("web", "skills", "fos-assistant"));
        when(skillClient.list(anyString())).thenReturn(List.of(
                new HermesSkill("shopping", "장을 본다", true),
                new HermesSkill("hermes-help", "Hermes 기본", true)));
    }

    private CurrentUser user(String email, String name) {
        AppUser user = users.findByEmail(email).orElseGet(() -> users.save(AppUser.of(email, name, 1L, UserRole.MEMBER)));
        return new CurrentUser(user.id(), user.email(), user.displayName(), user.groupId(), user.role());
    }

    private AgentExecution execution(CurrentUser user, Conversation conversation) {
        return executions.save(AgentExecution.builder()
                .userId(user.id())
                .conversationId(conversation.id())
                .agentId(agent.id())
                .profileName(AGENT_PROFILE)
                .costMode(CostMode.SUBSCRIPTION)
                .status(ExecutionStatus.SUCCEEDED)
                .startedAt(Instant.now())
                .build());
    }

    private void use(AgentExecution execution, String skillName, SkillUseSource source, Instant at) {
        uses.save(ExecutionSkillUse.of(execution.id(), skillName, source, at));
    }

    @Test
    void 편집자의_합계는_그_에이전트를_쓴_두_사용자의_호출을_합친다() {
        Map<String, SkillUsageSummary> byAgent = query.byAgent(agent.id());

        assertThat(byAgent).containsOnlyKeys("shopping");
        assertThat(byAgent.get("shopping")).isEqualTo(new SkillUsageSummary(3, T3));
    }

    @Test
    void 편집자의_스킬_목록에는_합계가_붙고_호출이_없는_스킬은_0_이다() {
        SkillList list = skills.list(dad, AGENT_CODE);

        assertThat(list.editable()).isTrue();
        assertThat(list.skills())
                .extracting(SkillListItem::name, SkillListItem::usage)
                .containsExactly(
                        tuple("hermes-help", new SkillUsageSummary(0, null)),
                        tuple("shopping", new SkillUsageSummary(3, T3)));
    }

    @Test
    void 편집자가_아닌_사용자의_스킬_목록에는_합계가_없다() {
        SkillList list = skills.list(kid, AGENT_CODE);

        assertThat(list.editable()).isFalse();
        assertThat(list.skills()).isNotEmpty().allSatisfy(item -> assertThat(item.usage()).isNull());
    }

    @Test
    void 각_사용자는_자기_호출만_보고_마지막_호출의_대화를_받는다() {
        when(currentUser.require()).thenReturn(dad);
        List<UsageDtos.MySkillUsageView> dadView = controller.mySkillUsage();
        when(currentUser.require()).thenReturn(kid);
        List<UsageDtos.MySkillUsageView> kidView = controller.mySkillUsage();

        assertThat(dadView).containsExactly(new UsageDtos.MySkillUsageView(
                AGENT_CODE, "가족 비서", "shopping", 2, T2, dadSecond.publicId()));
        assertThat(kidView).containsExactly(new UsageDtos.MySkillUsageView(
                AGENT_CODE, "가족 비서", "shopping", 1, T3, kidOnly.publicId()));
    }

    @Test
    void 마지막_호출의_대화를_지웠으면_lastConversationId_가_비고_합계는_남는다() {
        conversations.deleteIfActive(dadSecond.id(), dad.id(), Instant.now());

        List<UserSkillUsage> usages = query.byUser(dad.id());

        assertThat(usages).singleElement().satisfies(usage -> {
            assertThat(usage.count()).isEqualTo(2);
            assertThat(usage.lastInvokedAt()).isEqualTo(T2);
            assertThat(usage.lastConversationId()).isNull();
        });
    }

    @Test
    void 호출이_없는_사용자와_에이전트는_빈_결과다() {
        assertThat(query.byUser(9_999L)).isEmpty();
        assertThat(query.byAgent(9_999L)).isEmpty();
        assertThat(query.skillNamesByExecution(List.of())).isEmpty();
    }

    @Test
    void 실행_목록의_줄마다_그_실행에서_쓴_스킬_이름이_붙는다() {
        AgentExecution both = execution(dad, dadFirst);
        use(both, "shopping", SkillUseSource.COMMAND, T1);
        use(both, "shopping", SkillUseSource.MODEL, T1);
        use(both, "cooking", SkillUseSource.MODEL, T1);
        AgentExecution none = execution(dad, dadFirst);
        when(currentUser.require()).thenReturn(dad);

        List<UsageDtos.ExecutionView> page = controller.myExecutions(50);

        assertThat(page).filteredOn(view -> view.id().equals(both.id())).singleElement()
                .extracting(UsageDtos.ExecutionView::skillNames).isEqualTo(List.of("cooking", "shopping"));
        assertThat(page).filteredOn(view -> view.id().equals(none.id())).singleElement()
                .extracting(UsageDtos.ExecutionView::skillNames).isEqualTo(List.of());
    }
}
