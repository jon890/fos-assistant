package com.bifos.assistant.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
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

/** 실행 기록 커서 페이지의 경계와 사용자 범위를 실제 저장소로 확인한다. */
@BackendIntegrationTest
class ExecutionPagingTest {

    private static final Long USER_ID = 4_404L;
    private static final Long OTHER_USER_ID = 4_405L;

    @Autowired
    RootExecutionQuery query;

    @Autowired
    AgentExecutionRepository executions;

    @Autowired
    AgentRepository agents;

    private Agent agent;

    @BeforeEach
    void setUp() {
        executions.deleteAll();
        agent = agents.findByCode("execution-page-agent")
                .orElseGet(() -> agents.save(Agent.of(
                        "execution-page-agent",
                        "실행 페이지 에이전트",
                        "execution-page-agent",
                        "http://127.0.0.1:1/p/execution-page",
                        CostMode.SUBSCRIPTION,
                        CredentialScope.SHARED_HOUSEHOLD,
                        AgentVisibility.PRIVATE,
                        USER_ID,
                        Instant.now())));
    }

    @Test
    @DisplayName("동일 시각은 번호 역순으로 나누고 마지막과 빈 목록에는 다음 커서가 없다")
    void pagesRootsByStartedAtAndIdAndHandlesLastAndEmptyPage() {
        Instant at = Instant.parse("2026-10-08T00:00:00Z");
        AgentExecution old = execution(USER_ID, at.minusSeconds(1));
        AgentExecution sameFirst = execution(USER_ID, at);
        AgentExecution sameSecond = execution(USER_ID, at);
        AgentExecution newest = execution(USER_ID, at.plusSeconds(1));

        RootExecutionPage first = query.page(USER_ID, 2, null);
        RootExecutionPage last = query.page(USER_ID, 2, first.nextCursor());
        RootExecutionPage empty = query.page(OTHER_USER_ID, 2, null);

        assertThat(first.executions()).extracting(AgentExecution::id).containsExactly(newest.id(), sameSecond.id());
        assertThat(first.nextCursor()).isNotBlank();
        assertThat(last.executions()).extracting(AgentExecution::id).containsExactly(sameFirst.id(), old.id());
        assertThat(last.nextCursor()).isNull();
        assertThat(empty.executions()).isEmpty();
        assertThat(empty.nextCursor()).isNull();
    }

    @Test
    @DisplayName("커서 뒤에는 다른 사용자와 그 사이에 새로 생긴 실행이 섞이지 않는다")
    void cursorKeepsUserScopeAndExcludesRowsInsertedBeforeCursor() {
        Instant at = Instant.parse("2026-10-08T00:00:00Z");
        AgentExecution older = execution(USER_ID, at.minusSeconds(1));
        AgentExecution first = execution(USER_ID, at);
        execution(OTHER_USER_ID, at.plusSeconds(2));

        RootExecutionPage page = query.page(USER_ID, 1, null);
        AgentExecution inserted = execution(USER_ID, at.plusSeconds(1));
        RootExecutionPage next = query.page(USER_ID, 1, page.nextCursor());

        assertThat(page.executions()).extracting(AgentExecution::id).containsExactly(first.id());
        assertThat(next.executions()).extracting(AgentExecution::id).containsExactly(older.id());
        assertThat(next.executions()).extracting(AgentExecution::id).doesNotContain(inserted.id());
    }

    @Test
    @DisplayName("다른 사용자의 커서를 받아도 자기 루트만 시작 시각 순서로 읽고 자식은 뺀다")
    void foreignCursorStillReturnsOnlyOwnRootsInStartedAtOrder() {
        Instant at = Instant.parse("2026-10-08T00:00:00Z");
        AgentExecution newer = execution(USER_ID, at.plusSeconds(1));
        AgentExecution olderWithHigherId = execution(USER_ID, at);
        AgentExecution root = execution(USER_ID, at.minusSeconds(1));
        AgentExecution child = execution(USER_ID, at.plusSeconds(2), root.id(), root.id());
        execution(OTHER_USER_ID, at.plusSeconds(3));
        execution(OTHER_USER_ID, at.plusSeconds(4));
        RootExecutionPage otherPage = query.page(OTHER_USER_ID, 1, null);

        RootExecutionPage page = query.page(USER_ID, 10, otherPage.nextCursor());

        assertThat(page.executions()).extracting(AgentExecution::id)
                .containsExactly(newer.id(), olderWithHigherId.id(), root.id())
                .doesNotContain(child.id());
    }

    @Test
    @DisplayName("비었거나 잘못된 커서는 VALIDATION_FAILED 다")
    void invalidCursorIsRejected() {
        assertThatThrownBy(() -> query.page(USER_ID, 1, "invalid"))
                .isInstanceOf(ApiException.class)
                .extracting(failure -> ((ApiException) failure).code())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> query.page(USER_ID, 1, ""))
                .isInstanceOf(ApiException.class)
                .extracting(failure -> ((ApiException) failure).code())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    private AgentExecution execution(Long userId, Instant startedAt) {
        return execution(userId, startedAt, null, null);
    }

    private AgentExecution execution(Long userId, Instant startedAt, Long parentExecutionId, Long rootExecutionId) {
        return executions.save(AgentExecution.builder()
                .userId(userId)
                .agentId(agent.id())
                .parentExecutionId(parentExecutionId)
                .rootExecutionId(rootExecutionId)
                .profileName("execution-page")
                .costMode(CostMode.SUBSCRIPTION)
                .status(ExecutionStatus.SUCCEEDED)
                .startedAt(startedAt)
                .build());
    }
}
