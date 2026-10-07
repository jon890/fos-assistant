package com.bifos.assistant.usage;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.usage.application.RootExecutionPage;
import com.bifos.assistant.usage.application.RootExecutionQuery;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 루트 실행 목록 조회가 자식 여부와 대화의 공개 식별자를 함께 싣는지 본다. */
@BackendIntegrationTest
class RootExecutionQueryTest {

    private static final Long USER_ID = 4_402L;
    private static final Long OTHER_USER_ID = 4_403L;

    @Autowired
    RootExecutionQuery query;

    @Autowired
    AgentExecutionRepository executions;

    @Autowired
    AgentRepository agents;

    @Autowired
    ConversationRepository conversations;

    private Agent agent;

    @BeforeEach
    void setUp() {
        executions.deleteAll();
        agent = agents.findByCode("root-query-dad")
                .orElseGet(() -> agents.save(Agent.of(
                        "root-query-dad",
                        "루트 조회 아빠",
                        "dad",
                        "http://127.0.0.1:1/p/dad",
                        CostMode.SUBSCRIPTION,
                        CredentialScope.SHARED_HOUSEHOLD,
                        AgentVisibility.PRIVATE,
                        USER_ID,
                        Instant.now())));
    }

    @Test
    @DisplayName("루트만 번호 내림차순으로 담고 자식을 가진 루트와 대화의 공개 식별자를 붙인다")
    void pageHoldsRootsNewestFirstWithChildrenAndPublicIds() {
        Conversation conversation =
                conversations.save(Conversation.startedBy(USER_ID, "대화", agent.id(), Instant.now()));
        AgentExecution first = execution(USER_ID, conversation.id(), null, null);
        AgentExecution second = execution(USER_ID, conversation.id(), null, null);
        execution(USER_ID, conversation.id(), first.id(), first.id());

        RootExecutionPage page = query.page(USER_ID, 50);

        assertThat(page.executions()).extracting(AgentExecution::id).containsExactly(second.id(), first.id());
        assertThat(page.idsHavingChildren()).containsExactly(first.id());
        assertThat(page.conversationPublicIds()).hasSize(1).containsEntry(conversation.id(), conversation.publicId());
    }

    @Test
    @DisplayName("실행이 하나도 없는 사용자는 세 칸이 모두 빈다")
    void userWithoutExecutionsGetsEmptyPage() {
        execution(OTHER_USER_ID, null, null, null);

        RootExecutionPage page = query.page(USER_ID, 50);

        assertThat(page.executions()).isEmpty();
        assertThat(page.idsHavingChildren()).isEmpty();
        assertThat(page.conversationPublicIds()).isEmpty();
    }

    @Test
    @DisplayName("size 가 1 이면 가장 최근 루트 하나만 온다")
    void sizeOneReturnsOnlyNewestRoot() {
        execution(USER_ID, null, null, null);
        AgentExecution newest = execution(USER_ID, null, null, null);

        RootExecutionPage page = query.page(USER_ID, 1);

        assertThat(page.executions()).extracting(AgentExecution::id).containsExactly(newest.id());
    }

    private AgentExecution execution(Long userId, Long conversationId, Long parentId, Long rootId) {
        return executions.save(AgentExecution.builder()
                .userId(userId)
                .conversationId(conversationId)
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
