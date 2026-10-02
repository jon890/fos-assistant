package com.bifos.assistant.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.domain.type.ModelTier;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.application.ExecutionEventView;
import com.bifos.assistant.usage.application.ExecutionNode;
import com.bifos.assistant.usage.application.ExecutionTree;
import com.bifos.assistant.usage.application.ExecutionTreeService;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.ExecutionCost;
import com.bifos.assistant.usage.domain.ExecutionEvent;
import com.bifos.assistant.usage.domain.type.ExecutionEventType;
import com.bifos.assistant.usage.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.domain.type.ReasoningEffortSource;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.infra.ExecutionEventRepository;
import com.bifos.assistant.shared.domain.type.UserRole;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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

    @Autowired
    ExecutionTreeService trees;

    @Autowired
    AgentExecutionRepository executions;

    @Autowired
    ExecutionEventRepository events;

    @Autowired
    AgentRepository agents;

    @Autowired
    JdbcTemplate jdbc;

    private Agent agent;

    /** 어긋난 자료를 알리는 경고가 나갔는지 보려고 서비스의 로그를 받아 둔다. */
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void setUp() {
        logs = new ListAppender<>();
        logs.start();
        serviceLogger().addAppender(logs);
        events.deleteAll();
        executions.deleteAll();
        agent = agents.findByCode("tree-dad")
                .orElseGet(() -> agents.save(Agent.of(
                        "tree-dad",
                        "트리 아빠",
                        "dad",
                        "http://127.0.0.1:1/p/dad",
                        CostMode.SUBSCRIPTION,
                        CredentialScope.SHARED_HOUSEHOLD,
                        AgentVisibility.PRIVATE,
                        OWNER_ID,
                        Instant.now())));
    }

    @AfterEach
    void tearDown() {
        serviceLogger().detachAppender(logs);
        logs.stop();
    }

    private static Logger serviceLogger() {
        return (Logger) LoggerFactory.getLogger(ExecutionTreeService.class);
    }

    /** 어긋난 자료를 알린 경고만 고른다. */
    private List<String> warnings() {
        return logs.list.stream()
                .filter(event -> event.getLevel().toInt() >= Level.WARN_INT)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    @Test
    @DisplayName("사건은 순서대로 나오고 에이전트 이름이 붙는다")
    void eventsComeInOrderWithAgentNames() {
        AgentExecution execution = execution(OWNER_ID, null, null);
        event(execution, 3, ExecutionEventType.RUN_COMPLETED, null);
        event(execution, 1, ExecutionEventType.RUN_STARTED, null);
        event(execution, 2, ExecutionEventType.TOOL_STARTED, "fake-tool");

        ExecutionTree tree = trees.of(owner(), execution.id());

        assertThat(tree.truncated()).isFalse();
        assertThat(tree.root().executionId()).isEqualTo(execution.id());
        assertThat(tree.root().agentCode()).isEqualTo("tree-dad");
        assertThat(tree.root().agentName()).isEqualTo("트리 아빠");
        assertThat(tree.root().children()).isEmpty();
        assertThat(tree.root().events())
                .extracting(ExecutionEventView::sequence, ExecutionEventView::eventType)
                .containsExactly(tuple(1, "RUN_STARTED"), tuple(2, "TOOL_STARTED"), tuple(3, "RUN_COMPLETED"));
        assertThat(tree.root().events().get(1).toolName()).isEqualTo("fake-tool");
    }

    @Test
    @DisplayName("사건이 하나도 없는 실행도 오류 없이 나온다")
    void runWithoutAnyEventComesOutWithoutError() {
        AgentExecution execution = execution(OWNER_ID, null, null);

        ExecutionTree tree = trees.of(owner(), execution.id());

        assertThat(tree.root().events()).isEmpty();
        assertThat(tree.root().children()).isEmpty();
        assertThat(tree.truncated()).isFalse();
    }

    @Test
    @DisplayName("자식 실행은 children 에 담긴다")
    void childRunsAreInChildren() {
        AgentExecution root = execution(OWNER_ID, null, null);
        AgentExecution child = execution(OWNER_ID, root.id(), root.id());

        ExecutionTree tree = trees.of(owner(), root.id());

        assertThat(tree.root().children())
                .extracting(ExecutionNode::executionId)
                .containsExactly(child.id());
        assertThat(tree.truncated()).isFalse();
    }

    @Test
    @DisplayName("자식 실행의 에이전트 행이 없어도 트리가 나오고 그 노드의 에이전트 칸이 비어 있다")
    void treeComesOutWithEmptyAgentColumnWhenChildAgentRowIsMissing() {
        AgentExecution root = execution(OWNER_ID, null, null);
        Agent gone = agents.save(Agent.of(
                "tree-gone",
                "지운 아빠",
                "gone",
                "http://127.0.0.1:1/p/gone",
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE,
                OWNER_ID,
                Instant.now()));
        AgentExecution child = executions.save(AgentExecution.builder()
                .userId(OWNER_ID)
                .agentId(gone.id())
                .parentExecutionId(root.id())
                .rootExecutionId(root.id())
                .profileName("gone")
                .costMode(CostMode.SUBSCRIPTION)
                .status(ExecutionStatus.SUCCEEDED)
                .startedAt(Instant.now())
                .build());
        agents.deleteById(gone.id());

        ExecutionTree tree = trees.of(owner(), root.id());

        assertThat(tree.root().agentCode()).isEqualTo("tree-dad");
        assertThat(tree.root().children()).singleElement().satisfies(node -> {
            assertThat(node.executionId()).isEqualTo(child.id());
            assertThat(node.agentCode()).isNull();
            assertThat(node.agentName()).isNull();
        });
    }

    @Test
    @DisplayName("자식의 번호로 물어도 루트부터 나온다")
    void askingByChildIdStartsFromRoot() {
        AgentExecution root = execution(OWNER_ID, null, null);
        AgentExecution child = execution(OWNER_ID, root.id(), root.id());

        ExecutionTree tree = trees.of(owner(), child.id());

        assertThat(tree.root().executionId()).isEqualTo(root.id());
        assertThat(tree.root().children())
                .extracting(ExecutionNode::executionId)
                .containsExactly(child.id());
    }

    @Test
    @DisplayName("남의 실행 번호로 물으면 없는 것과 같은 오류다")
    void askingByOthersRunIdGivesSameErrorAsMissing() {
        AgentExecution other = execution(STRANGER_ID, null, null);

        assertThatThrownBy(() -> trees.of(owner(), other.id()))
                .isInstanceOf(ApiException.class)
                .extracting(failure -> ((ApiException) failure).code())
                .isEqualTo(ErrorCode.EXECUTION_NOT_FOUND);
    }

    @Test
    @DisplayName("없는 실행 번호로 물어도 같은 오류다")
    void askingByMissingRunIdGivesSameError() {
        assertThatThrownBy(() -> trees.of(owner(), 9_999_999L))
                .isInstanceOf(ApiException.class)
                .extracting(failure -> ((ApiException) failure).code())
                .isEqualTo(ErrorCode.EXECUTION_NOT_FOUND);
    }

    @Test
    @DisplayName("루트를 찾아 올라가는 길이 순환이면 멈추고 트리가 잘렸다고 알린다")
    void stopsAndReportsTruncatedTreeWhenWalkUpToRootIsCyclic() {
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
    @DisplayName("상한보다 깊은 트리는 상한까지만 내고 마지막 노드가 잘렸다고 알린다")
    void emitsTreeDeeperThanLimitOnlyToLimitAndFlagsLastNodeTruncated() {
        AgentExecution root = execution(OWNER_ID, null, null);
        List<Long> chain = new ArrayList<>(List.of(root.id()));
        for (int depth = 2; depth <= MAX_DEPTH + 1; depth++) {
            chain.add(
                    execution(OWNER_ID, chain.get(chain.size() - 1), root.id()).id());
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
        // 일부러 자른 것이므로 어긋난 자료로 알리지 않는다.
        assertThat(warnings()).isEmpty();
    }

    @Test
    @DisplayName("루트에 닿지 않는 실행은 트리에 넣지 않는다")
    void leavesRunNotReachingRootOutOfTree() {
        AgentExecution root = execution(OWNER_ID, null, null);
        AgentExecution child = execution(OWNER_ID, root.id(), root.id());
        AgentExecution orphan = execution(OWNER_ID, 9_999_999L, root.id());

        ExecutionTree tree = trees.of(owner(), root.id());

        assertThat(flatten(tree.root())).containsExactly(root.id(), child.id()).doesNotContain(orphan.id());
        assertThat(tree.truncated()).isFalse();
        assertThat(warnings()).singleElement().asString().contains(String.valueOf(orphan.id()));
    }

    @Test
    @DisplayName("MEMBER 역할에게는 공개한 도구와 도구가 아닌 사건의 detail 만 실린다")
    void memberGetsDetailOnlyOfPublicToolsAndNonToolEvents() {
        AgentExecution execution = executionWithToolAndSubagent();

        ExecutionTree tree = trees.of(ownerAs(UserRole.MEMBER), execution.id());

        assertThat(tree.root().events())
                .extracting(ExecutionEventView::eventType, ExecutionEventView::detail)
                .as("terminal 의 명령 원문은 비우고 검색어와 하위 에이전트 목표는 싣는다")
                .containsExactly(
                        tuple("TOOL_STARTED", null),
                        tuple("TOOL_STARTED", "제주 날씨"),
                        tuple("SUBAGENT_STARTED", "숙소를 찾는다"));
        // 응답에서만 빼고 저장한 값은 그대로 둔다.
        assertThat(events.findByExecutionIdInOrderByExecutionIdAscSequenceAsc(List.of(execution.id())))
                .extracting(ExecutionEvent::detail)
                .containsExactly("python3 run.py", "제주 날씨", "숙소를 찾는다");
    }

    @Test
    @DisplayName("ADMIN 역할에게는 도구의 명령 원문이 그대로 실린다")
    void adminGetsToolCommandTextAsIs() {
        AgentExecution execution = executionWithToolAndSubagent();

        ExecutionTree tree = trees.of(ownerAs(UserRole.ADMIN), execution.id());

        assertThat(tree.root().events())
                .extracting(ExecutionEventView::detail)
                .containsExactly("python3 run.py", "제주 날씨", "숙소를 찾는다");
    }

    @Test
    @DisplayName("MEMBER 역할의 노드에는 모델과 토큰과 금액과 시각 구간이 비고 걸린 시간과 단계는 남는다")
    void memberNodeHasNoModelTokensCostOrTimestampsButKeepsLatencyAndTier() {
        AgentExecution execution = detailedExecution();

        ExecutionNode node = trees.of(ownerAs(UserRole.MEMBER), execution.id()).root();

        assertThat(node.agentCode()).as("agentCode").isNull();
        assertThat(node.provider()).as("provider").isNull();
        assertThat(node.model()).as("model").isNull();
        assertThat(node.reasoningEffort()).as("reasoningEffort").isNull();
        assertThat(node.reasoningEffortSource()).as("reasoningEffortSource").isNull();
        assertThat(node.inputTokens()).as("inputTokens").isNull();
        assertThat(node.cachedInputTokens()).as("cachedInputTokens").isNull();
        assertThat(node.outputTokens()).as("outputTokens").isNull();
        assertThat(node.totalTokens()).as("totalTokens").isNull();
        assertThat(node.estimatedCostMicros()).as("estimatedCostMicros").isNull();
        assertThat(node.requestReceivedAt()).as("requestReceivedAt").isNull();
        assertThat(node.submittedAt()).as("submittedAt").isNull();
        assertThat(node.firstDeltaAt()).as("firstDeltaAt").isNull();
        assertThat(node.finishedAt()).as("finishedAt").isNull();
        // MEMBER 역할에게도 보이는 값은 남는다.
        assertThat(node.agentName()).isEqualTo("트리 아빠");
        assertThat(node.status()).isEqualTo("FAILED");
        assertThat(node.modelTier()).isEqualTo("DEEP");
        assertThat(node.latencyMs()).isEqualTo(1_234L);
        assertThat(node.startedAt()).isNotNull();
    }

    @Test
    @DisplayName("ADMIN 역할의 노드에는 모델과 토큰과 금액과 시각 구간이 그대로 실린다")
    void adminNodeKeepsModelTokensCostAndTimestamps() {
        AgentExecution execution = detailedExecution();

        ExecutionNode node = trees.of(ownerAs(UserRole.ADMIN), execution.id()).root();

        assertThat(node.agentCode()).isEqualTo("tree-dad");
        assertThat(node.provider()).isEqualTo("example-provider");
        assertThat(node.model()).isEqualTo("example-model-large");
        assertThat(node.reasoningEffort()).isEqualTo("high");
        assertThat(node.reasoningEffortSource()).isEqualTo("REQUESTED");
        assertThat(node.modelTier()).isEqualTo("DEEP");
        assertThat(node.inputTokens()).isEqualTo(100L);
        assertThat(node.cachedInputTokens()).isEqualTo(40L);
        assertThat(node.outputTokens()).isEqualTo(20L);
        assertThat(node.totalTokens()).isEqualTo(120L);
        assertThat(node.estimatedCostMicros()).isEqualTo(5_000L);
        assertThat(node.latencyMs()).isEqualTo(1_234L);
        assertThat(node.requestReceivedAt()).isEqualTo(STARTED_AT.minusMillis(50));
        assertThat(node.submittedAt()).isEqualTo(STARTED_AT.plusMillis(10));
        assertThat(node.firstDeltaAt()).isEqualTo(STARTED_AT.plusMillis(300));
        assertThat(node.startedAt()).isEqualTo(STARTED_AT);
        assertThat(node.finishedAt()).isEqualTo(STARTED_AT.plusMillis(1_234));
    }

    @Test
    @DisplayName("MEMBER 역할의 사건에는 모델과 토큰이 비고 PROVIDER SWITCHED 사건이 없으며 RUN FAILED 의 detail 은 남는다")
    void memberEventsHaveNoModelOrTokensAndNoProviderSwitchedButKeepRunFailedDetail() {
        AgentExecution execution = executionWithSwitchAndFailure();

        ExecutionTree tree = trees.of(ownerAs(UserRole.MEMBER), execution.id());

        assertThat(tree.root().events())
                .extracting(ExecutionEventView::eventType, ExecutionEventView::detail)
                .containsExactly(tuple("SUBAGENT_COMPLETED", "숙소를 찾는다"), tuple("RUN_FAILED", "PROVIDER_BLOCKED"));
        assertThat(tree.root().events().get(0)).satisfies(event -> {
            assertThat(event.model()).as("model").isNull();
            assertThat(event.inputTokens()).as("inputTokens").isNull();
            assertThat(event.outputTokens()).as("outputTokens").isNull();
            assertThat(event.subagentName()).isEqualTo("researcher");
            assertThat(event.durationMs()).isEqualTo(900L);
        });
        // 응답에서만 빼고 저장한 사건은 그대로 둔다.
        assertThat(events.findByExecutionIdInOrderByExecutionIdAscSequenceAsc(List.of(execution.id())))
                .extracting(ExecutionEvent::eventType)
                .containsExactly(
                        ExecutionEventType.PROVIDER_SWITCHED,
                        ExecutionEventType.SUBAGENT_COMPLETED,
                        ExecutionEventType.RUN_FAILED);
    }

    @Test
    @DisplayName("ADMIN 역할의 사건에는 모델과 토큰이 실리고 PROVIDER SWITCHED 사건도 나온다")
    void adminEventsKeepModelTokensAndProviderSwitched() {
        AgentExecution execution = executionWithSwitchAndFailure();

        ExecutionTree tree = trees.of(ownerAs(UserRole.ADMIN), execution.id());

        assertThat(tree.root().events())
                .extracting(ExecutionEventView::eventType, ExecutionEventView::detail)
                .containsExactly(
                        tuple("PROVIDER_SWITCHED", "example-provider/example-model-small"),
                        tuple("SUBAGENT_COMPLETED", "숙소를 찾는다"),
                        tuple("RUN_FAILED", "PROVIDER_BLOCKED"));
        assertThat(tree.root().events().get(1)).satisfies(event -> {
            assertThat(event.model()).isEqualTo("example-model-small");
            assertThat(event.inputTokens()).isEqualTo(70L);
            assertThat(event.outputTokens()).isEqualTo(9L);
        });
    }

    private static final Instant STARTED_AT = Instant.parse("2026-09-10T01:00:00Z");

    /** 내부 값이 모두 채워진 실패 실행이다. */
    private AgentExecution detailedExecution() {
        AgentExecution execution = AgentExecution.builder()
                .userId(OWNER_ID)
                .conversationId(7L)
                .agentId(agent.id())
                .profileName("dad")
                .provider("example-provider")
                .model("example-model-large")
                .reasoningEffort("high")
                .reasoningEffortSource(ReasoningEffortSource.REQUESTED)
                .modelTier(ModelTier.DEEP)
                .requestReceivedAt(STARTED_AT.minusMillis(50))
                .costMode(CostMode.SUBSCRIPTION)
                .status(ExecutionStatus.FAILED)
                .errorCode("PROVIDER_BLOCKED")
                .tokens(100L, 40L, 20L, 120L)
                .cost(new ExecutionCost(5_000L, null, "USD", "2026-09"))
                .timing(STARTED_AT, STARTED_AT.plusMillis(1_234))
                .build();
        execution.markSubmitted(STARTED_AT.plusMillis(10));
        execution.markFirstDelta(STARTED_AT.plusMillis(300));
        return executions.save(execution);
    }

    /** 다른 모델로 넘어간 사건, 모델과 토큰을 가진 하위 에이전트 완료 사건, 실패 사건을 가진 실행이다. */
    private AgentExecution executionWithSwitchAndFailure() {
        AgentExecution execution = execution(OWNER_ID, null, null);
        event(execution, 1, ExecutionEventType.PROVIDER_SWITCHED, null, "example-provider/example-model-small");
        events.save(ExecutionEvent.builder()
                .executionId(execution.id())
                .sequence(2)
                .eventType(ExecutionEventType.SUBAGENT_COMPLETED)
                .subagentName("researcher")
                .durationMs(900L)
                .detail("숙소를 찾는다")
                .model("example-model-small")
                .inputTokens(70L)
                .outputTokens(9L)
                .occurredAt(Instant.now())
                .build());
        event(execution, 3, ExecutionEventType.RUN_FAILED, null, "PROVIDER_BLOCKED");
        return execution;
    }

    /** 공개하지 않는 도구, 공개하는 도구, 하위 에이전트 사건을 하나씩 가진 실행이다. */
    private AgentExecution executionWithToolAndSubagent() {
        AgentExecution execution = execution(OWNER_ID, null, null);
        event(execution, 1, ExecutionEventType.TOOL_STARTED, "terminal", "python3 run.py");
        event(execution, 2, ExecutionEventType.TOOL_STARTED, "web_search", "제주 날씨");
        event(execution, 3, ExecutionEventType.SUBAGENT_STARTED, null, "숙소를 찾는다");
        return execution;
    }

    private static List<Long> flatten(ExecutionNode node) {
        List<Long> ids = new ArrayList<>();
        ids.add(node.executionId());
        node.children().forEach(child -> ids.addAll(flatten(child)));
        return ids;
    }

    private static CurrentUser owner() {
        return ownerAs(UserRole.ADMIN);
    }

    private static CurrentUser ownerAs(UserRole role) {
        return new CurrentUser(OWNER_ID, "dad@example.com", "dad", 1L, role);
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
        event(execution, sequence, type, toolName, null);
    }

    private void event(
            AgentExecution execution, int sequence, ExecutionEventType type, String toolName, String detail) {
        events.save(ExecutionEvent.builder()
                .executionId(execution.id())
                .sequence(sequence)
                .eventType(type)
                .toolName(toolName)
                .detail(detail)
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
        jdbc.update("update agent_execution set parent_execution_id = ? where id = ?", parent.id(), execution.id());
    }
}
