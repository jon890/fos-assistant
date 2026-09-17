package com.bifos.assistant.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentVisibility;
import com.bifos.assistant.agent.domain.CostMode;
import com.bifos.assistant.agent.domain.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.application.ExecutionEventView;
import com.bifos.assistant.usage.application.ExecutionNode;
import com.bifos.assistant.usage.application.ExecutionTree;
import com.bifos.assistant.usage.application.ExecutionTreeService;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.ExecutionEvent;
import com.bifos.assistant.usage.domain.ExecutionEventType;
import com.bifos.assistant.usage.domain.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.infra.ExecutionEventRepository;
import com.bifos.assistant.user.domain.UserRole;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** 실행 하나를 그 사건과 자식 실행까지 묶어 내는지 확인한다. */
@SpringBootTest
@ActiveProfiles("test")
class ExecutionTreeServiceTest {

    private static final Long OWNER_ID = 4_301L;
    private static final Long STRANGER_ID = 4_302L;

    /** 서비스가 올라가고 내려가는 길에 두는 상한이다. 값을 바꾸면 이 테스트가 함께 알려야 한다. */
    private static final int MAX_DEPTH = 8;

    @Autowired ExecutionTreeService trees;
    @Autowired AgentExecutionRepository executions;
    @Autowired ExecutionEventRepository events;
    @Autowired AgentRepository agents;
    @Autowired JdbcTemplate jdbc;

    private Agent agent;

    @BeforeEach
    void 준비한다() {
        events.deleteAll();
        executions.deleteAll();
        agent = agents.findByCode("tree-dad").orElseGet(() -> agents.save(Agent.of(
                "tree-dad",
                "나무 아빠",
                "dad",
                "http://127.0.0.1:1/p/dad",
                "openai-codex",
                "gpt-5.5",
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE,
                OWNER_ID)));
    }

    @Test
    void 사건은_순서대로_나오고_에이전트_이름이_붙는다() {
        AgentExecution execution = execution(OWNER_ID, null, null);
        event(execution, 3, ExecutionEventType.RUN_COMPLETED, null);
        event(execution, 1, ExecutionEventType.RUN_STARTED, null);
        event(execution, 2, ExecutionEventType.TOOL_STARTED, "fake-tool");

        ExecutionTree tree = trees.of(owner(), execution.id());

        assertThat(tree.truncated()).isFalse();
        assertThat(tree.root().executionId()).isEqualTo(execution.id());
        assertThat(tree.root().agentCode()).isEqualTo("tree-dad");
        assertThat(tree.root().agentName()).isEqualTo("나무 아빠");
        assertThat(tree.root().children()).isEmpty();
        assertThat(tree.root().events())
                .extracting(ExecutionEventView::sequence, ExecutionEventView::eventType)
                .containsExactly(
                        tuple(1, "RUN_STARTED"),
                        tuple(2, "TOOL_STARTED"),
                        tuple(3, "RUN_COMPLETED"));
        assertThat(tree.root().events().get(1).toolName()).isEqualTo("fake-tool");
    }

    @Test
    void 사건이_하나도_없는_실행도_오류_없이_나온다() {
        AgentExecution execution = execution(OWNER_ID, null, null);

        ExecutionTree tree = trees.of(owner(), execution.id());

        assertThat(tree.root().events()).isEmpty();
        assertThat(tree.root().children()).isEmpty();
        assertThat(tree.truncated()).isFalse();
    }

    @Test
    void 자식_실행은_children_에_담긴다() {
        AgentExecution root = execution(OWNER_ID, null, null);
        AgentExecution child = execution(OWNER_ID, root.id(), root.id());

        ExecutionTree tree = trees.of(owner(), root.id());

        assertThat(tree.root().children())
                .extracting(ExecutionNode::executionId)
                .containsExactly(child.id());
        assertThat(tree.truncated()).isFalse();
    }

    @Test
    void 자식의_번호로_물어도_뿌리부터_나온다() {
        AgentExecution root = execution(OWNER_ID, null, null);
        AgentExecution child = execution(OWNER_ID, root.id(), root.id());

        ExecutionTree tree = trees.of(owner(), child.id());

        assertThat(tree.root().executionId()).isEqualTo(root.id());
        assertThat(tree.root().children())
                .extracting(ExecutionNode::executionId)
                .containsExactly(child.id());
    }

    @Test
    void 남의_실행_번호로_물으면_없는_것과_같은_오류다() {
        AgentExecution other = execution(STRANGER_ID, null, null);

        assertThatThrownBy(() -> trees.of(owner(), other.id()))
                .isInstanceOf(ApiException.class)
                .extracting(failure -> ((ApiException) failure).code())
                .isEqualTo(ErrorCode.EXECUTION_NOT_FOUND);
    }

    @Test
    void 없는_실행_번호로_물어도_같은_오류다() {
        assertThatThrownBy(() -> trees.of(owner(), 9_999_999L))
                .isInstanceOf(ApiException.class)
                .extracting(failure -> ((ApiException) failure).code())
                .isEqualTo(ErrorCode.EXECUTION_NOT_FOUND);
    }

    @Test
    void 뿌리를_찾아_올라가는_길이_순환이면_멈추고_나무가_잘렸다고_알린다() {
        AgentExecution first = execution(OWNER_ID, null, null);
        AgentExecution second = execution(OWNER_ID, first.id(), null);
        pointParentTo(first, second);

        ExecutionTree tree = trees.of(owner(), first.id());

        assertThat(tree.truncated()).isTrue();
        // 잘린 것이 노드의 아래가 아니라 위쪽이라 노드에는 적지 않는다.
        assertThat(tree.root().truncated()).isFalse();
        assertThat(tree.root().executionId()).isIn(first.id(), second.id());
    }

    @Test
    void 상한보다_깊은_나무는_상한까지만_내고_마지막_노드가_잘렸다고_알린다() {
        AgentExecution root = execution(OWNER_ID, null, null);
        List<Long> chain = new ArrayList<>(List.of(root.id()));
        for (int depth = 2; depth <= MAX_DEPTH + 1; depth++) {
            chain.add(execution(OWNER_ID, chain.get(chain.size() - 1), root.id()).id());
        }

        ExecutionTree tree = trees.of(owner(), root.id());

        List<Long> shown = new ArrayList<>();
        ExecutionNode node = tree.root();
        while (true) {
            shown.add(node.executionId());
            if (node.children().isEmpty()) {
                break;
            }
            node = node.children().get(0);
        }

        assertThat(shown).isEqualTo(chain.subList(0, MAX_DEPTH));
        assertThat(node.truncated()).isTrue();
        assertThat(tree.truncated()).isTrue();
    }

    @Test
    void 뿌리에_닿지_않는_실행은_나무에_넣지_않는다() {
        AgentExecution root = execution(OWNER_ID, null, null);
        AgentExecution child = execution(OWNER_ID, root.id(), root.id());
        AgentExecution orphan = execution(OWNER_ID, 9_999_999L, root.id());

        ExecutionTree tree = trees.of(owner(), root.id());

        assertThat(flatten(tree.root()))
                .containsExactly(root.id(), child.id())
                .doesNotContain(orphan.id());
        assertThat(tree.truncated()).isFalse();
    }

    private static List<Long> flatten(ExecutionNode node) {
        List<Long> ids = new ArrayList<>();
        ids.add(node.executionId());
        node.children().forEach(child -> ids.addAll(flatten(child)));
        return ids;
    }

    private static CurrentUser owner() {
        return new CurrentUser(OWNER_ID, "dad@example.com", "dad", 1L, UserRole.ADMIN);
    }

    private AgentExecution execution(Long userId, Long parentId, Long rootId) {
        return executions.save(AgentExecution.builder()
                .userId(userId)
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

    private void event(AgentExecution execution, int sequence, ExecutionEventType type, String toolName) {
        events.save(ExecutionEvent.builder()
                .executionId(execution.id())
                .sequence(sequence)
                .eventType(type)
                .toolName(toolName)
                .occurredAt(Instant.now())
                .build());
    }

    /**
     * 부모를 나중에 가리키게 만든다.
     *
     * <p>엔티티에 부모를 바꾸는 길이 없다. 서로를 부모로 가리키는 어긋난 자료는 정상 경로로 만들 수
     * 없으므로 여기서만 데이터베이스를 직접 고친다.
     */
    private void pointParentTo(AgentExecution execution, AgentExecution parent) {
        jdbc.update(
                "update agent_execution set parent_execution_id = ? where id = ?",
                parent.id(),
                execution.id());
    }
}
