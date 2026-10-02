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
import com.bifos.assistant.chat.application.ConversationWriter;
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
import com.bifos.assistant.usage.application.UsageSummaryService;
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
import org.junit.jupiter.api.DisplayName;
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

    @Autowired
    SkillUsageQuery query;

    @Autowired
    SkillService skills;

    @Autowired
    ExecutionSkillUseRepository uses;

    @Autowired
    AgentExecutionRepository executions;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    ConversationWriter conversationWriter;

    @Autowired
    AgentRepository agents;

    @Autowired
    AppUserRepository users;

    @Autowired
    AgentService agentService;

    @Autowired
    ExecutionTreeService trees;

    @Autowired
    UsageSummaryService summaries;

    @MockitoBean
    HermesSkillClient skillClient;

    @MockitoBean
    HermesToolsetClient toolsets;

    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
    private UsageController controller;

    private CurrentUser dad;
    private CurrentUser kid;
    private Agent agent;
    private Conversation dadFirst;
    private Conversation dadSecond;
    private Conversation kidOnly;

    @BeforeEach
    void setUp() {
        uses.deleteAll();
        executions.deleteAll();
        agents.findByCode(AGENT_CODE).ifPresent(agents::delete);
        dad = user("usage-dad@example.com", "아빠");
        kid = user("usage-kid@example.com", "아이");
        agent = agents.save(Agent.of(
                AGENT_CODE,
                "가족 비서",
                AGENT_PROFILE,
                "http://agent-runtime.test/p/family",
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.GROUP,
                dad.id(),
                Instant.now()));
        dadFirst = conversations.save(Conversation.startedBy(dad.id(), "장보기 첫째", agent.id(), Instant.now()));
        dadSecond = conversations.save(Conversation.startedBy(dad.id(), "장보기 둘째", agent.id(), Instant.now()));
        kidOnly = conversations.save(Conversation.startedBy(kid.id(), "아이 장보기", agent.id(), Instant.now()));
        // 아빠가 둘, 아이가 하나 읽었다. 마지막 호출은 아이의 것이다.
        use(execution(dad, dadFirst), "shopping", SkillUseSource.MODEL, T1);
        use(execution(dad, dadSecond), "shopping", SkillUseSource.COMMAND, T2);
        use(execution(kid, kidOnly), "shopping", SkillUseSource.MODEL, T3);
        controller = new UsageController(executions, currentUser, agentService, trees, conversations, query, summaries);
        when(toolsets.readEnabled(anyString(), anyString())).thenReturn(List.of("web", "skills", "fos-assistant"));
        when(skillClient.list(anyString()))
                .thenReturn(List.of(
                        new HermesSkill("shopping", "장을 본다", true), new HermesSkill("hermes-help", "Hermes 기본", true)));
    }

    private CurrentUser user(String email, String name) {
        AppUser user = users.findByEmail(email)
                .orElseGet(() -> users.save(AppUser.of(email, name, 1L, UserRole.MEMBER, Instant.now())));
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
    @DisplayName("편집자의 합계는 그 에이전트를 쓴 두 사용자의 호출을 합친다")
    void editorTotalSumsCallsOfTwoUsersOfThatAgent() {
        Map<String, SkillUsageSummary> byAgent = query.byAgent(agent.id());

        assertThat(byAgent).containsOnlyKeys("shopping");
        assertThat(byAgent.get("shopping")).isEqualTo(new SkillUsageSummary(3, T3));
    }

    @Test
    @DisplayName("편집자의 스킬 목록에는 합계가 붙고 호출이 없는 스킬은 0 이다")
    void editorSkillListHasTotalsAndSkillWithoutCallsIsZero() {
        SkillList list = skills.list(dad, AGENT_CODE);

        assertThat(list.editable()).isTrue();
        assertThat(list.skills())
                .extracting(SkillListItem::name, SkillListItem::usage)
                .containsExactly(
                        tuple("hermes-help", new SkillUsageSummary(0, null)),
                        tuple("shopping", new SkillUsageSummary(3, T3)));
    }

    @Test
    @DisplayName("편집자가 아닌 사용자의 스킬 목록에는 합계가 없다")
    void nonEditorSkillListHasNoTotals() {
        SkillList list = skills.list(kid, AGENT_CODE);

        assertThat(list.editable()).isFalse();
        assertThat(list.skills())
                .isNotEmpty()
                .allSatisfy(item -> assertThat(item.usage()).isNull());
    }

    @Test
    @DisplayName("각 사용자는 자기 호출만 보고 마지막 호출의 대화를 받는다")
    void eachUserSeesOnlyOwnCallsAndGetsConversationOfLastCall() {
        when(currentUser.require()).thenReturn(dad);
        List<UsageDtos.MySkillUsageView> dadView = controller.mySkillUsage();
        when(currentUser.require()).thenReturn(kid);
        List<UsageDtos.MySkillUsageView> kidView = controller.mySkillUsage();

        // 둘 다 MEMBER 역할이라 에이전트 코드는 비어 온다(ADR-063).
        assertThat(dadView)
                .containsExactly(
                        new UsageDtos.MySkillUsageView(null, "가족 비서", "shopping", 2, T2, dadSecond.publicId()));
        assertThat(kidView)
                .containsExactly(new UsageDtos.MySkillUsageView(null, "가족 비서", "shopping", 1, T3, kidOnly.publicId()));
    }

    @Test
    @DisplayName("마지막 호출의 대화를 지웠으면 lastConversationId 가 비고 합계는 남는다")
    void lastConversationIdIsEmptyWhenItsConversationDeletedAndTotalRemains() {
        conversationWriter.deleteIfActive(dadSecond.id(), dad.id(), Instant.now());

        List<UserSkillUsage> usages = query.byUser(dad.id());

        assertThat(usages).singleElement().satisfies(usage -> {
            assertThat(usage.count()).isEqualTo(2);
            assertThat(usage.lastInvokedAt()).isEqualTo(T2);
            assertThat(usage.lastConversationId()).isNull();
        });
    }

    @Test
    @DisplayName("호출이 없는 사용자와 에이전트는 빈 결과다")
    void userAndAgentWithoutCallsGiveEmptyResult() {
        assertThat(query.byUser(9_999L)).isEmpty();
        assertThat(query.byAgent(9_999L)).isEmpty();
        assertThat(query.skillNamesByExecution(List.of())).isEmpty();
    }

    @Test
    @DisplayName("실행 목록의 줄마다 그 실행에서 쓴 스킬 이름이 붙는다")
    void runListRowsCarrySkillNameUsedInThatRun() {
        AgentExecution both = execution(dad, dadFirst);
        use(both, "shopping", SkillUseSource.COMMAND, T1);
        use(both, "shopping", SkillUseSource.MODEL, T1);
        use(both, "cooking", SkillUseSource.MODEL, T1);
        AgentExecution none = execution(dad, dadFirst);
        when(currentUser.require()).thenReturn(dad);

        List<UsageDtos.ExecutionView> page = controller.myExecutions(50);

        assertThat(page)
                .filteredOn(view -> view.id().equals(both.id()))
                .singleElement()
                .extracting(UsageDtos.ExecutionView::skillNames)
                .isEqualTo(List.of("cooking", "shopping"));
        assertThat(page)
                .filteredOn(view -> view.id().equals(none.id()))
                .singleElement()
                .extracting(UsageDtos.ExecutionView::skillNames)
                .isEqualTo(List.of());
    }

    @Test
    @DisplayName("한 실행에 같은 이름의 COMMAND 와 MODEL 이 함께 있으면 1회이고 다른 실행이 더해지면 2회다")
    void sameNameCommandAndModelInOneRunCountOnceAndAnotherRunMakesTwo() {
        AgentExecution commanded = execution(dad, dadFirst);
        use(commanded, "weekly-plan", SkillUseSource.COMMAND, T1);
        use(commanded, "weekly-plan", SkillUseSource.MODEL, T1);

        assertThat(query.byAgent(agent.id()).get("weekly-plan")).as("에이전트 합계").isEqualTo(new SkillUsageSummary(1, T1));
        assertThat(query.byUser(dad.id()))
                .filteredOn(usage -> usage.skillName().equals("weekly-plan"))
                .singleElement()
                .satisfies(usage -> assertThat(usage.count()).as("사용자 합계").isEqualTo(1));

        AgentExecution again = execution(dad, dadSecond);
        use(again, "weekly-plan", SkillUseSource.MODEL, T2);

        assertThat(query.byAgent(agent.id()).get("weekly-plan"))
                .as("다른 실행을 더한 에이전트 합계")
                .isEqualTo(new SkillUsageSummary(2, T2));
        assertThat(query.byUser(dad.id()))
                .filteredOn(usage -> usage.skillName().equals("weekly-plan"))
                .singleElement()
                .satisfies(usage -> {
                    assertThat(usage.count()).as("다른 실행을 더한 사용자 합계").isEqualTo(2);
                    assertThat(usage.lastConversationId()).isEqualTo(dadSecond.publicId());
                });
    }
}
