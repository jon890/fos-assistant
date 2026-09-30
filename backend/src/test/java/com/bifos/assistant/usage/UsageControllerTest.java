package com.bifos.assistant.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentVisibility;
import com.bifos.assistant.agent.domain.CostMode;
import com.bifos.assistant.agent.domain.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.skill.application.SkillUsageQuery;
import com.bifos.assistant.usage.application.ExecutionTreeService;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.presentation.UsageController;
import com.bifos.assistant.usage.presentation.UsageDtos;
import com.bifos.assistant.user.domain.UserRole;
import jakarta.persistence.EntityManagerFactory;
import java.time.Instant;
import java.util.List;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 사용량 목록이 자식 여부를 어떻게 붙이는지 본다.
 *
 * <p>질의 수를 세려고 통계를 켠 별도의 문맥으로 띄운다. 세는 것은 <b>목록 길이가 늘어도 자식 확인이 한
 * 번인가</b> 하나다. 목록 조회 전체의 질의 수가 아니다.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@ActiveProfiles("test")
class UsageControllerTest {

    private static final Long USER_ID = 4_401L;

    @Autowired AgentExecutionRepository executions;
    @Autowired AgentRepository agents;
    @Autowired AgentService agentService;
    @Autowired ExecutionTreeService trees;
    @Autowired ConversationRepository conversations;
    @Autowired SkillUsageQuery skillUsage;
    @Autowired EntityManagerFactory entityManagers;

    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
    private UsageController controller;
    private Agent agent;

    @BeforeEach
    void setUp() {
        executions.deleteAll();
        agent = agents.findByCode("list-dad").orElseGet(() -> agents.save(Agent.of(
                "list-dad",
                "목록 아빠",
                "dad",
                "http://127.0.0.1:1/p/dad",
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE,
                USER_ID)));
        controller = new UsageController(executions, currentUser, agentService, trees, conversations, skillUsage);
        when(currentUser.require())
                .thenReturn(new CurrentUser(USER_ID, "dad@example.com", "dad", 1L, UserRole.ADMIN));
    }

    @Test
    @DisplayName("자식을 가진 실행만 hasChildren 이 참이다")
    void onlyRunsWithChildrenHaveHasChildrenTrue() {
        AgentExecution parent = execution(null, null);
        execution(parent.id(), parent.id());
        AgentExecution alone = execution(null, null);

        List<UsageDtos.ExecutionView> page = controller.myExecutions(50);

        assertThat(page)
                .filteredOn(view -> view.id().equals(parent.id()))
                .singleElement()
                .extracting(UsageDtos.ExecutionView::hasChildren)
                .isEqualTo(true);
        assertThat(page)
                .filteredOn(view -> view.id().equals(alone.id()))
                .singleElement()
                .extracting(UsageDtos.ExecutionView::hasChildren)
                .isEqualTo(false);
    }

    @Test
    @DisplayName("요청한 effort 를 싣고 고르지 않은 실행은 null 이다")
    void carriesRequestedEffortAndUnchosenRunIsNull() {
        AgentExecution chosen = execution(null, null, "high");
        AgentExecution byDefault = execution(null, null);

        List<UsageDtos.ExecutionView> page = controller.myExecutions(50);

        assertThat(page)
                .filteredOn(view -> view.id().equals(chosen.id()))
                .singleElement()
                .extracting(UsageDtos.ExecutionView::reasoningEffort)
                .isEqualTo("high");
        assertThat(page)
                .filteredOn(view -> view.id().equals(byDefault.id()))
                .singleElement()
                .extracting(UsageDtos.ExecutionView::reasoningEffort)
                .isNull();
    }

    @Test
    @DisplayName("에이전트 행이 없어도 두 줄이 나오고 없는 쪽의 에이전트 칸만 빈다")
    void twoRowsComeOutWithoutAgentRowAndOnlyMissingSideAgentColumnIsEmpty() {
        Agent gone = agents.save(Agent.of(
                "list-gone",
                "지운 아빠",
                "gone",
                "http://127.0.0.1:1/p/gone",
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE,
                USER_ID));
        AgentExecution kept = execution(null, null);
        AgentExecution orphaned = executions.save(AgentExecution.builder()
                .userId(USER_ID)
                .conversationId(7L)
                .agentId(gone.id())
                .profileName("gone")
                .costMode(CostMode.SUBSCRIPTION)
                .status(ExecutionStatus.SUCCEEDED)
                .startedAt(Instant.now())
                .build());
        agents.deleteById(gone.id());

        List<UsageDtos.ExecutionView> page = controller.myExecutions(50);

        assertThat(page).hasSize(2);
        assertThat(page)
                .filteredOn(view -> view.id().equals(orphaned.id()))
                .singleElement()
                .satisfies(view -> {
                    assertThat(view.agentCode()).isNull();
                    assertThat(view.agentName()).isNull();
                });
        assertThat(page)
                .filteredOn(view -> view.id().equals(kept.id()))
                .singleElement()
                .satisfies(view -> {
                    assertThat(view.agentCode()).isEqualTo("list-dad");
                    assertThat(view.agentName()).isEqualTo("목록 아빠");
                });
    }

    /**
     * 목록 조회가 내는 질의 수다.
     *
     * <p>목록을 읽는 것 하나와 자식을 확인하는 것 하나, 대화 번호를 공개 식별자로 바꾸는 것 하나, 실행에서 쓴 스킬 이름을 읽는 것 하나,
     * 에이전트를 한 번에 읽는 것 하나다.
     */
    private static final long QUERIES_PER_LIST = 5;

    @Test
    @DisplayName("목록이 길어져도 자식을 확인하는 질의는 늘지 않는다")
    void childCheckQueriesDoNotGrowAsListGrows() {
        long few = queriesForList(2);
        long many = queriesForList(10);

        assertThat(few)
                .as("실행 2개일 때 %d 번 질의했다", few)
                .isEqualTo(QUERIES_PER_LIST);
        assertThat(many)
                .as("실행 10개일 때 %d 번 질의했다. 2개일 때는 %d 번이었다", many, few)
                .isEqualTo(QUERIES_PER_LIST);
    }

    /** 실행을 그만큼 넣고 목록을 한 번 부르면서 질의 수를 센다. */
    private long queriesForList(int howMany) {
        executions.deleteAll();
        for (int i = 0; i < howMany; i++) {
            execution(null, null);
        }
        Statistics statistics = entityManagers.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        List<UsageDtos.ExecutionView> page = controller.myExecutions(200);

        assertThat(page).hasSize(howMany);
        return statistics.getQueryExecutionCount();
    }

    private AgentExecution execution(Long parentId, Long rootId) {
        return execution(parentId, rootId, null);
    }

    private AgentExecution execution(Long parentId, Long rootId, String reasoningEffort) {
        return executions.save(AgentExecution.builder()
                .userId(USER_ID)
                .conversationId(7L)
                .agentId(agent.id())
                .parentExecutionId(parentId)
                .rootExecutionId(rootId)
                .profileName("dad")
                .reasoningEffort(reasoningEffort)
                .costMode(CostMode.SUBSCRIPTION)
                .status(ExecutionStatus.SUCCEEDED)
                .startedAt(Instant.now())
                .build());
    }
}
