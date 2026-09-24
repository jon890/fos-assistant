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
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 사용량 목록이 자식 여부를 어떻게 붙이는지 본다.
 *
 * <p>질의 수를 세려고 통계를 켠 별도의 문맥으로 띄운다. 세는 것은 <b>목록 길이가 늘어도 자식 확인이 한
 * 번인가</b> 하나다. 목록 조회 전체의 질의 수가 아니다. 에이전트를 실행마다 읽는 것은 이 변경이 만든
 * 것이 아니다.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@ActiveProfiles("test")
class UsageControllerTest {

    private static final Long USER_ID = 4_401L;

    @Autowired AgentExecutionRepository executions;
    @Autowired AgentRepository agents;
    @Autowired AgentService agentService;
    @Autowired ExecutionTreeService trees;
    @Autowired EntityManagerFactory entityManagers;

    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
    private UsageController controller;
    private Agent agent;

    @BeforeEach
    void 준비한다() {
        executions.deleteAll();
        agent = agents.findByCode("list-dad").orElseGet(() -> agents.save(Agent.of(
                "list-dad",
                "목록 아빠",
                "dad",
                "http://127.0.0.1:1/p/dad",
                "openai-codex",
                "example-model",
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE,
                USER_ID)));
        controller = new UsageController(executions, currentUser, agentService, trees);
        when(currentUser.require())
                .thenReturn(new CurrentUser(USER_ID, "dad@example.com", "dad", 1L, UserRole.ADMIN));
    }

    @Test
    void 자식을_가진_실행만_hasChildren_이_참이다() {
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

    /**
     * 목록 조회가 내는 질의 수다.
     *
     * <p>목록을 읽는 것 하나와 자식을 확인하는 것 하나다. 실행마다 읽는 에이전트는 {@code findById} 라
     * 엔티티 적재로 세어지고 이 지표에 들어오지 않는다.
     */
    private static final long QUERIES_PER_LIST = 2;

    @Test
    void 목록이_길어져도_자식을_확인하는_질의는_늘지_않는다() {
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
        return executions.save(AgentExecution.builder()
                .userId(USER_ID)
                .conversationId(7L)
                .agentId(agent.id())
                .parentExecutionId(parentId)
                .rootExecutionId(rootId)
                .profileName("dad")
                .costMode(CostMode.SUBSCRIPTION)
                .status(ExecutionStatus.SUCCEEDED)
                .startedAt(Instant.now())
                .build());
    }
}
