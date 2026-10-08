package com.bifos.assistant.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.hermes.HermesRunsClient;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.usage.application.ContextSourceRef;
import com.bifos.assistant.usage.application.CostEstimator;
import com.bifos.assistant.usage.application.ExecutionContextSnapshot;
import com.bifos.assistant.usage.application.ExecutionContextSourceWriter;
import com.bifos.assistant.usage.application.ExecutionRecorder;
import com.bifos.assistant.usage.application.ExecutionTreeService;
import com.bifos.assistant.usage.application.UserExecutionLimiter;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.ExecutionContextSource;
import com.bifos.assistant.usage.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.usage.infra.ExecutionContextSourceRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 실행마다 실은 문맥 항목의 참조를 남기고 관리자에게만 실행 트리로 보이는지 확인한다(ADR-071). */
@BackendIntegrationTest
class ExecutionContextSourceTest {

    private static final Long USER_ID = 4_711L;

    /** 실은 순서대로 둔 참조 셋이다. 저장된 순서가 이 순서와 같아야 한다. */
    private static final List<ContextSourceRef> SOURCES = List.of(
            new ContextSourceRef("MEMORY_ALWAYS", "memory:9101", "INLINE", "FRESH"),
            new ContextSourceRef("MEMORY_INDEX", "memory:9102", "TITLE_ONLY", "FRESH"),
            new ContextSourceRef("MEMORY_INDEX", "memory:9103", "OMITTED", "UNKNOWN"));

    @Autowired
    ExecutionRecorder recorder;

    @Autowired
    ExecutionTreeService trees;

    @Autowired
    AgentExecutionRepository executions;

    @Autowired
    ExecutionContextSourceRepository contextSources;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    Clock clock;

    @Autowired
    CostEstimator costs;

    @Autowired
    UserExecutionLimiter limiter;

    @Autowired
    PlatformTransactionManager transactionManager;

    /** 실제로 돈 모델을 읽는 세션 조회는 대역이 받는다. 기록 규칙만 보는 검사다. */
    @Autowired
    HermesRunsClient hermes;

    private Conversation conversation;

    @BeforeEach
    void setUp() {
        contextSources.deleteAll();
        executions.deleteAll();
        conversation = conversations.save(Conversation.startedBy(USER_ID, "문맥 출처", null, Instant.now()));
    }

    @Test
    @DisplayName("스냅숏에 담은 참조 셋이 실은 순서대로 position 0, 1, 2 로 저장된다")
    void storesSnapshotSourcesInOrderFromPositionZero() {
        AgentExecution execution = start(recorder, adminUser());

        assertThat(contextSources.findByIdExecutionIdOrderByIdPositionAsc(execution.id()))
                .as("실행 %s 에 남은 문맥 참조", execution.id())
                .extracting(
                        ExecutionContextSource::position,
                        ExecutionContextSource::source,
                        ExecutionContextSource::sourceRef,
                        ExecutionContextSource::bodyMode,
                        ExecutionContextSource::freshness)
                .containsExactly(
                        tuple(0, "MEMORY_ALWAYS", "memory:9101", "INLINE", "FRESH"),
                        tuple(1, "MEMORY_INDEX", "memory:9102", "TITLE_ONLY", "FRESH"),
                        tuple(2, "MEMORY_INDEX", "memory:9103", "OMITTED", "UNKNOWN"));
    }

    @Test
    @DisplayName("참조가 없는 스냅숏이면 아무 줄도 남기지 않는다")
    void storesNothingWhenSnapshotHasNoSources() {
        AgentExecution execution = recorder.start(
                adminUser(),
                conversation.executionConversation(),
                agent(),
                null,
                null,
                new ExecutionContextSnapshot(10L, null, null, 0));

        assertThat(contextSources.findByIdExecutionIdOrderByIdPositionAsc(execution.id()))
                .isEmpty();
    }

    @Test
    @DisplayName("참조 저장이 실패해도 실행 줄은 RUNNING 으로 만들어지고 예외가 올라오지 않는다")
    void executionIsCreatedWhenSourceStoreFails() {
        ExecutionContextSourceRepository failing = mock(ExecutionContextSourceRepository.class);
        when(failing.saveAll(any())).thenThrow(new DataAccessResourceFailureException("저장소가 닫혔다"));
        ExecutionRecorder swapped = new ExecutionRecorder(
                clock,
                executions,
                costs,
                hermes,
                limiter,
                new ExecutionContextSourceWriter(failing, transactionManager, clock));

        AgentExecution execution = start(swapped, adminUser());

        assertThat(executions.findById(execution.id()))
                .as("참조 저장이 실패한 뒤의 실행 줄")
                .hasValueSatisfying(saved -> assertThat(saved.status()).isEqualTo(ExecutionStatus.RUNNING));
        assertThat(contextSources.findByIdExecutionIdOrderByIdPositionAsc(execution.id()))
                .isEmpty();
    }

    @Test
    @DisplayName("바깥 트랜잭션 안에서 참조 저장이 실패해도 바깥 트랜잭션은 커밋되고 실행 줄이 남는다")
    void outerTransactionCommitsWhenSourceStoreFails() {
        // source_ref 칸은 80자다. 넘는 참조는 데이터베이스가 거절해 실제 저장소에서 저장이 실패한다
        List<ContextSourceRef> tooLong =
                List.of(new ContextSourceRef("MEMORY_ALWAYS", "memory:" + "9".repeat(80), "INLINE", "FRESH"));
        TransactionTemplate outer = new TransactionTemplate(transactionManager);

        AgentExecution execution = outer.execute(status -> recorder.start(
                adminUser(),
                conversation.executionConversation(),
                agent(),
                null,
                null,
                new ExecutionContextSnapshot(120L, null, null, 1, tooLong)));

        assertThat(executions.findById(execution.id()))
                .as("바깥 트랜잭션이 끝난 뒤의 실행 줄")
                .hasValueSatisfying(saved -> assertThat(saved.status()).isEqualTo(ExecutionStatus.RUNNING));
        assertThat(contextSources.findByIdExecutionIdOrderByIdPositionAsc(execution.id()))
                .isEmpty();
    }

    @Test
    @DisplayName("덧붙일 position 을 다른 호출이 먼저 쓰면 position 을 다시 읽어 그 뒤에 한 번 더 붙인다")
    void appendRetriesOnceAfterPositionTaken() {
        AgentExecution execution = start(recorder, adminUser());
        // 처음 읽은 마지막 position 은 다른 호출이 줄을 넣기 전 값이다. 그 다음 읽기는 실제 값을 본다
        ExecutionContextSourceRepository racing = racingRepository(execution.id(), 1, true);

        new ExecutionContextSourceWriter(racing, transactionManager, clock)
                .append(execution.id(), new ContextSourceRef("MEMORY_READ", "memory:9104", "INLINE", "UNKNOWN"));

        assertThat(contextSources.findByIdExecutionIdOrderByIdPositionAsc(execution.id()))
                .as("실행 %s 에 남은 문맥 참조", execution.id())
                .extracting(ExecutionContextSource::position, ExecutionContextSource::source)
                .containsExactly(
                        tuple(0, "MEMORY_ALWAYS"),
                        tuple(1, "MEMORY_INDEX"),
                        tuple(2, "MEMORY_INDEX"),
                        tuple(3, "MEMORY_READ"));
    }

    @Test
    @DisplayName("다시 해 봐도 position 이 겹치면 던지지 않고 줄을 남기지 않는다")
    void appendGivesUpWithoutThrowingWhenPositionStaysTaken() {
        AgentExecution execution = start(recorder, adminUser());
        ExecutionContextSourceRepository racing = racingRepository(execution.id(), 0, false);

        new ExecutionContextSourceWriter(racing, transactionManager, clock)
                .append(execution.id(), new ContextSourceRef("MEMORY_READ", "memory:9104", "INLINE", "UNKNOWN"));

        assertThat(contextSources.findByIdExecutionIdOrderByIdPositionAsc(execution.id()))
                .as("실행 %s 에 남은 문맥 참조", execution.id())
                .extracting(ExecutionContextSource::source)
                .containsExactly("MEMORY_ALWAYS", "MEMORY_INDEX", "MEMORY_INDEX");
    }

    @Test
    @DisplayName("관리자가 실행 트리를 읽으면 루트 노드에 저장한 참조 셋이 실린다")
    void adminSeesStoredSourcesOnRootNode() {
        AgentExecution execution = start(recorder, adminUser());

        assertThat(trees.of(adminUser(), execution.id()).root().contextSources())
                .as("관리자가 읽은 루트 노드의 문맥 참조")
                .containsExactlyElementsOf(SOURCES);
    }

    @Test
    @DisplayName("MEMBER 역할이 같은 트리를 읽으면 문맥 참조가 null 이다")
    void memberSeesNoSources() {
        AgentExecution execution = start(recorder, adminUser());

        assertThat(trees.of(memberUser(), execution.id()).root().contextSources())
                .as("MEMBER 역할이 읽은 루트 노드의 문맥 참조")
                .isNull();
    }

    private AgentExecution start(ExecutionRecorder using, CurrentUser user) {
        return using.start(
                user,
                conversation.executionConversation(),
                agent(),
                null,
                null,
                new ExecutionContextSnapshot(120L, null, null, 1, SOURCES));
    }

    /**
     * 저장은 실제 저장소로 넘기고 마지막 position 만 낡은 값으로 주는 대역이다. 같은 실행의 다른 호출이 먼저 줄을 넣은 상황을 만든다.
     *
     * @param stalePosition 처음 읽을 때 줄 낡은 마지막 position
     * @param catchesUp 참이면 두 번째 읽기부터 실제 값을 준다. 거짓이면 늘 낡은 값을 준다
     */
    private ExecutionContextSourceRepository racingRepository(Long executionId, int stalePosition, boolean catchesUp) {
        ExecutionContextSourceRepository racing = mock(ExecutionContextSourceRepository.class);
        if (catchesUp) {
            when(racing.lastPosition(eq(executionId)))
                    .thenReturn(stalePosition)
                    .thenAnswer(invocation -> contextSources.lastPosition(executionId));
        } else {
            when(racing.lastPosition(eq(executionId))).thenReturn(stalePosition);
        }
        when(racing.saveAndFlush(any()))
                .thenAnswer(invocation -> contextSources.saveAndFlush(invocation.getArgument(0)));
        return racing;
    }

    private static CurrentUser adminUser() {
        return new CurrentUser(USER_ID, "parent@example.com", "parent", 1L, UserRole.ADMIN);
    }

    /** 같은 사람이 관리자 역할이 아니라고 본다. 주인 확인은 통과하고 역할만 다르다. */
    private static CurrentUser memberUser() {
        return new CurrentUser(USER_ID, "parent@example.com", "parent", 1L, UserRole.MEMBER);
    }

    private static Agent agent() {
        return Agent.of(
                "context-parent",
                "Parent",
                "parent",
                "http://127.0.0.1:1/p/parent",
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE,
                USER_ID,
                Instant.now());
    }
}
