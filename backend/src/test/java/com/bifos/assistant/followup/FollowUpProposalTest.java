package com.bifos.assistant.followup;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.followup.application.FollowUpService;
import com.bifos.assistant.followup.application.model.FollowUpProposalOutcome;
import com.bifos.assistant.followup.domain.FollowUp;
import com.bifos.assistant.followup.domain.type.FollowUpStatus;
import com.bifos.assistant.followup.infra.FollowUpRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.TestClock;
import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 에이전트의 할 일 제안과 네 억제 규칙을 실제 DB 로 본다. 규칙은 {@code docs/backend/follow-up.md} 의 「제안 억제」 다.
 *
 * <p>시각은 이 검사의 시계가 정한다. 사용자, 대화, 실행은 검사마다 새로 만들어 다른 검사의 줄과 섞이지 않게 하고, 끝나면 지운다.
 */
@BackendIntegrationTest
class FollowUpProposalTest {

    private static final Instant NOW = Instant.parse("2026-10-05T09:00:00Z");
    private static final String TITLE = "할 일 검사 7391";

    @Autowired
    TestClock clock;

    @Autowired
    FollowUpService followUps;

    @Autowired
    FollowUpRepository repository;

    @Autowired
    AppUserRepository users;

    @Autowired
    AgentRepository agents;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    AgentExecutionRepository executions;

    @Autowired
    JdbcTemplate jdbc;

    private final List<Long> createdUsers = new ArrayList<>();
    private CurrentUser dad;
    private Agent agent;
    private Conversation conversation;

    @BeforeEach
    void setUp() {
        clock.set(NOW);
        dad = member();
        agent = agentOf(dad);
        conversation = conversationOf(dad);
    }

    @AfterEach
    void tearDown() {
        for (Long userId : createdUsers) {
            jdbc.update("DELETE FROM follow_up WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM agent_execution WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM conversation WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM agent WHERE owner_user_id = ?", userId);
        }
        createdUsers.clear();
        clock.set(NOW);
    }

    @Test
    @DisplayName("대화의 실행이 제안하면 그 실행이 제안한 PROPOSED 줄이 생긴다")
    void createsProposedRowForExecution() {
        AgentExecution run = run();

        FollowUpProposalOutcome outcome = propose(run, TITLE);

        assertThat(outcome).isEqualTo(FollowUpProposalOutcome.CREATED);
        List<FollowUp> rows = rowsOf(dad);
        assertThat(rows).hasSize(1);
        FollowUp row = rows.getFirst();
        assertThat(row.status()).isEqualTo(FollowUpStatus.PROPOSED);
        assertThat(row.proposedByExecutionId()).isEqualTo(run.id());
        assertThat(row.conversationId()).isEqualTo(conversation.id());
        assertThat(row.title()).isEqualTo(TITLE);
    }

    @Test
    @DisplayName("같은 제목을 다른 실행에서 다시 제안하면 DUPLICATE 이고 줄 수가 그대로다")
    void returnsDuplicateForSameOpenTitle() {
        propose(run(), TITLE);

        FollowUpProposalOutcome again = propose(run(), "  할 일   검사 7391 ");

        assertThat(again).isEqualTo(FollowUpProposalOutcome.DUPLICATE);
        assertThat(rowsOf(dad)).hasSize(1);
    }

    @Test
    @DisplayName("같은 대화에서 거절한 제목은 29일 뒤에도 DECLINED_BEFORE 이고 줄이 생기지 않는다")
    void declinesTitleRejectedInSameConversation() {
        propose(run(), TITLE);
        FollowUp proposed = rowsOf(dad).getFirst();
        followUps.reject(dad, proposed.publicId());

        FollowUpProposalOutcome right = propose(run(), TITLE);
        clock.set(NOW.plus(Duration.ofDays(29)));
        FollowUpProposalOutcome later = propose(run(), TITLE);

        assertThat(right).isEqualTo(FollowUpProposalOutcome.DECLINED_BEFORE);
        assertThat(later).isEqualTo(FollowUpProposalOutcome.DECLINED_BEFORE);
        assertThat(rowsOf(dad)).hasSize(1);
    }

    @Test
    @DisplayName("거절하고 31일이 지나면 같은 대화에서 같은 제목을 다시 제안할 수 있다")
    void allowsRejectedTitleAfterCooldown() {
        propose(run(), TITLE);
        followUps.reject(dad, rowsOf(dad).getFirst().publicId());

        clock.set(NOW.plus(Duration.ofDays(31)));
        FollowUpProposalOutcome outcome = propose(run(), TITLE);

        assertThat(outcome).isEqualTo(FollowUpProposalOutcome.CREATED);
        assertThat(rowsOf(dad))
                .extracting(FollowUp::status)
                .containsExactly(FollowUpStatus.REJECTED, FollowUpStatus.PROPOSED);
    }

    @Test
    @DisplayName("다른 대화에서 거절한 제목은 이 대화에서 제안할 수 있다")
    void allowsTitleRejectedInOtherConversation() {
        propose(run(), TITLE);
        followUps.reject(dad, rowsOf(dad).getFirst().publicId());
        conversation = conversationOf(dad);

        assertThat(propose(run(), TITLE)).isEqualTo(FollowUpProposalOutcome.CREATED);
    }

    @Test
    @DisplayName("같은 대화에 PROPOSED 가 셋이면 넷째 제안은 TOO_MANY_PROPOSALS 이다")
    void limitsOpenProposalsPerConversation() {
        List<FollowUpProposalOutcome> first =
                List.of(propose(run(), "할 일 검사 1"), propose(run(), "할 일 검사 2"), propose(run(), "할 일 검사 3"));

        FollowUpProposalOutcome fourth = propose(run(), "할 일 검사 4");

        assertThat(first).containsOnly(FollowUpProposalOutcome.CREATED);
        assertThat(fourth).isEqualTo(FollowUpProposalOutcome.TOO_MANY_PROPOSALS);
        assertThat(rowsOf(dad)).hasSize(3);
    }

    @Test
    @DisplayName("점검 대화의 7일 지난 제안은 남아 있지만 새 제안 상한에서 빠진다")
    void excludesOldCheckProposalsFromLimit() {
        conversation = conversations.save(Conversation.startedForCheck(dad.id(), "점검 제안 검사", agent.id(), NOW));
        propose(run(), "점검 할 일 1");
        propose(run(), "점검 할 일 2");
        propose(run(), "점검 할 일 3");

        clock.set(NOW.plus(Duration.ofDays(7)));
        assertThat(propose(run(), "점검 할 일 4")).isEqualTo(FollowUpProposalOutcome.TOO_MANY_PROPOSALS);

        clock.set(NOW.plus(Duration.ofDays(7)).plusSeconds(1));
        assertThat(propose(run(), "점검 할 일 4")).isEqualTo(FollowUpProposalOutcome.CREATED);
        assertThat(rowsOf(dad)).hasSize(4);
        assertThat(rowsOf(dad)).allMatch(row -> row.status() == FollowUpStatus.PROPOSED);
    }

    @Test
    @DisplayName("보통 대화는 7일이 지나도 열린 제안 셋을 모두 센다")
    void keepsOldChatProposalsInLimit() {
        propose(run(), "보통 할 일 1");
        propose(run(), "보통 할 일 2");
        propose(run(), "보통 할 일 3");

        clock.set(NOW.plus(Duration.ofDays(8)));

        assertThat(propose(run(), "보통 할 일 4")).isEqualTo(FollowUpProposalOutcome.TOO_MANY_PROPOSALS);
        assertThat(rowsOf(dad)).hasSize(3);
    }

    @Test
    @DisplayName("한 실행이 다른 제목 셋을 제안하면 셋째가 TOO_MANY_IN_RUN 이다")
    void limitsProposalsPerExecution() {
        AgentExecution run = run();

        List<FollowUpProposalOutcome> outcomes =
                List.of(propose(run, "할 일 검사 1"), propose(run, "할 일 검사 2"), propose(run, "할 일 검사 3"));

        assertThat(outcomes)
                .containsExactly(
                        FollowUpProposalOutcome.CREATED,
                        FollowUpProposalOutcome.CREATED,
                        FollowUpProposalOutcome.TOO_MANY_IN_RUN);
        assertThat(rowsOf(dad)).hasSize(2);
    }

    @Test
    @DisplayName("같은 제목을 동시에 여러 실행이 제안해도 줄은 하나이고 나머지는 DUPLICATE 다")
    void keepsOneRowForConcurrentProposals() throws Exception {
        int callers = 3;
        List<AgentExecution> runs = new ArrayList<>();
        for (int i = 0; i < callers; i++) {
            runs.add(run());
        }
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            List<Future<FollowUpProposalOutcome>> results = new ArrayList<>();
            for (AgentExecution run : runs) {
                Callable<FollowUpProposalOutcome> proposeOnce = () -> {
                    start.await();
                    return propose(run, TITLE);
                };
                results.add(pool.submit(proposeOnce));
            }
            start.countDown();
            List<FollowUpProposalOutcome> outcomes = new ArrayList<>();
            for (Future<FollowUpProposalOutcome> result : results) {
                outcomes.add(result.get());
            }

            assertThat(outcomes)
                    .as("동시 제안의 결과")
                    .containsExactlyInAnyOrder(
                            FollowUpProposalOutcome.CREATED,
                            FollowUpProposalOutcome.DUPLICATE,
                            FollowUpProposalOutcome.DUPLICATE);
            assertThat(rowsOf(dad)).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
    }

    private FollowUpProposalOutcome propose(AgentExecution run, String title) {
        return followUps.propose(dad, conversation.id(), run.id(), title, null, false);
    }

    private List<FollowUp> rowsOf(CurrentUser user) {
        return repository.findByUserIdAndStatusInOrderByIdAsc(user.id(), List.of(FollowUpStatus.values()));
    }

    /** 지금 대화에서 도는 아빠의 실행이다. 실행마다 따로 세므로 검사마다 새로 만든다. */
    private AgentExecution run() {
        return executions.save(AgentExecution.builder()
                .userId(dad.id())
                .agentId(agent.id())
                .conversationId(conversation.id())
                .profileName(agent.hermesProfile())
                .hermesSessionId("fos-" + UUID.randomUUID())
                .costMode(CostMode.SUBSCRIPTION)
                .status(ExecutionStatus.RUNNING)
                .startedAt(clock.instant())
                .build());
    }

    private CurrentUser member() {
        String email = "follow-up-proposal-" + UUID.randomUUID() + "@example.com";
        AppUser user = users.save(AppUser.of(email, email, 1L, UserRole.MEMBER, NOW));
        createdUsers.add(user.id());
        return new CurrentUser(user.id(), user.email(), user.displayName(), user.groupId(), user.role());
    }

    private Agent agentOf(CurrentUser owner) {
        String code = "follow-up-proposal-" + UUID.randomUUID().toString().substring(0, 8);
        return agents.save(Agent.of(
                code,
                "할 일 검사 도우미",
                code,
                "http://agent-runtime.test/p/" + code,
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE,
                owner.id(),
                NOW));
    }

    private Conversation conversationOf(CurrentUser owner) {
        return conversations.save(Conversation.startedBy(owner.id(), "할 일 검사 대화", agent.id(), NOW));
    }
}
