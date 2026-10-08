package com.bifos.assistant.proactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.proactive.application.AutonomyPolicyService;
import com.bifos.assistant.proactive.application.ValueEvaluationOverviews;
import com.bifos.assistant.proactive.domain.ProactiveCheck;
import com.bifos.assistant.proactive.domain.ProactiveCheckProblem;
import com.bifos.assistant.proactive.domain.ProblemEvidence;
import com.bifos.assistant.proactive.domain.type.CheckOutcome;
import com.bifos.assistant.proactive.domain.type.CheckSkippedReason;
import com.bifos.assistant.proactive.domain.type.CheckTrigger;
import com.bifos.assistant.proactive.domain.type.ProblemStatus;
import com.bifos.assistant.proactive.infra.AutonomyDecisionRepository;
import com.bifos.assistant.proactive.infra.ProactiveCheckProblemRepository;
import com.bifos.assistant.proactive.infra.ProactiveCheckRepository;
import com.bifos.assistant.proactive.infra.ValueEvaluationRepository;
import com.bifos.assistant.proactive.presentation.ValueEvaluationAdminController;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.GlobalExceptionHandler;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 관리자 화면이 읽는 가치 평가 묶음 경로를 HTTP 모양까지 본다.
 *
 * <p>기본 설정에서 판단 provider 는 Hermes 를 부르지 않고 {@code FALLBACK / PROVIDER_UNAVAILABLE} 을 낸다. 검사들이 H2 를 함께 쓰므로
 * 사용자 번호는 다른 검사와 겹치지 않는 값을 쓰고, 만든 줄은 끝날 때 지운다.
 */
@BackendIntegrationTest
class ValueEvaluationOverviewTest {

    private static final String BODY = "{\"provider\":\"hermes\"}";

    @Autowired
    ValueEvaluationOverviews overviews;

    @Autowired
    AutonomyPolicyService autonomy;

    @Autowired
    AgentRepository agents;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    ProactiveCheckRepository checks;

    @Autowired
    ProactiveCheckProblemRepository problems;

    @Autowired
    ValueEvaluationRepository evaluations;

    @Autowired
    AutonomyDecisionRepository decisions;

    @Autowired
    TransactionTemplate transactions;

    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
    private final CurrentUser admin = new CurrentUser(960_001L, "admin@example.com", "관리자", 1L, UserRole.ADMIN);
    private final CurrentUser member = new CurrentUser(960_001L, "admin@example.com", "관리자", 1L, UserRole.MEMBER);
    private final CurrentUser other = new CurrentUser(960_002L, "other@example.com", "다른 사람", 1L, UserRole.ADMIN);
    private final List<Long> createdAgents = new ArrayList<>();
    private final List<Long> createdConversations = new ArrayList<>();
    private final List<Long> createdChecks = new ArrayList<>();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ValueEvaluationAdminController(overviews, currentUser))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        // 대역이어도 관리자 확인은 실제 판정을 탄다. 그래야 MEMBER 역할의 거절을 볼 수 있다.
        doCallRealMethod().when(currentUser).requireAdmin();
        when(currentUser.require()).thenReturn(admin);
    }

    @AfterEach
    void tearDown() {
        transactions.executeWithoutResult(status -> {
            for (Long checkId : createdChecks) {
                evaluations.findAll().stream()
                        .filter(row -> row.checkId().equals(checkId))
                        .forEach(row -> {
                            decisions.deleteAll(decisions.findByUserIdAndEvaluationIdInOrderByIdAsc(
                                    row.userId(), List.of(row.id())));
                            evaluations.delete(row);
                        });
                problems.deleteAll(problems.findByCheckIdAndStatusOrderByIdAsc(checkId, ProblemStatus.ACCEPTED));
            }
            checks.deleteAllById(createdChecks);
            conversations.deleteAllById(createdConversations);
            agents.deleteAllById(createdAgents);
        });
    }

    @Test
    @DisplayName("고를 살펴보기가 없으면 check 와 evaluation 이 null 이고 decisions 가 빈 배열이다")
    void emptyOverviewWhenNothingToPick() throws Exception {
        Agent agent = agent();

        latest(agent)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.check", nullValue()))
                .andExpect(jsonPath("$.evaluation", nullValue()))
                .andExpect(jsonPath("$.decisions", hasSize(0)));
    }

    @Test
    @DisplayName("받아들인 후보가 있는 살펴보기를 평가하고 판정한 묶음을 201 로 주고 이어 읽으면 같은 묶음이다")
    void runsEvaluationAndDecisionThenReadsSameBundle() throws Exception {
        Agent agent = agent();
        Conversation conversation = conversation(agent);
        ProactiveCheck check = succeeded(agent, conversation, CheckTrigger.MANUAL);
        accepted(check, conversation, "합성 문제");

        String body = run(check)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.check.id").value(check.id()))
                .andExpect(jsonPath("$.check.trigger").value("MANUAL"))
                .andExpect(jsonPath("$.check.acceptedCandidates").value(1))
                .andExpect(jsonPath("$.evaluation.outcome").value("FALLBACK"))
                .andExpect(jsonPath("$.evaluation.failure").value("PROVIDER_UNAVAILABLE"))
                .andExpect(jsonPath("$.evaluation.candidates", hasSize(1)))
                .andExpect(jsonPath("$.evaluation.candidates[0].problem").value("합성 문제"))
                .andExpect(jsonPath("$.decisions", hasSize(1)))
                .andExpect(jsonPath("$.decisions[0].level").value("IGNORE"))
                .andExpect(jsonPath("$.decisions[0].reasons", hasItem("EVALUATION_NOT_USABLE")))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(body).doesNotContain("rootExecutionId", "executionId", "inputs");

        latest(agent)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.check.id").value(check.id()))
                .andExpect(jsonPath("$.evaluation.id")
                        .value(evaluations
                                .findFirstByUserIdAndCheckIdOrderByIdDesc(admin.id(), check.id())
                                .orElseThrow()
                                .id()))
                .andExpect(jsonPath("$.evaluation.outcome").value("FALLBACK"))
                .andExpect(jsonPath("$.decisions", hasSize(1)))
                .andExpect(jsonPath("$.decisions[0].level").value("IGNORE"))
                .andExpect(jsonPath("$.decisions[0].reasons", hasItem("EVALUATION_NOT_USABLE")));
    }

    @Test
    @DisplayName("더 늦은 자동 실행 살펴보기와 후보 없이 건너뛴 살펴보기가 있어도 받아들인 후보가 있는 앞의 사람 살펴보기를 고른다")
    void skipsAutonomyAndCandidateLessChecks() throws Exception {
        Agent agent = agent();
        Conversation conversation = conversation(agent);
        ProactiveCheck manual = succeeded(agent, conversation, CheckTrigger.MANUAL);
        accepted(manual, conversation, "합성 문제");
        ProactiveCheck autonomy = succeeded(agent, conversation, CheckTrigger.AUTONOMY);
        accepted(autonomy, conversation, "자동 실행 문제");
        ProactiveCheck skipped =
                ProactiveCheck.started(admin.id(), agent.id(), conversation.id(), CheckTrigger.SCHEDULED, false, now());
        skipped.skip(CheckSkippedReason.UNREAD_REPORT, now());
        createdChecks.add(checks.save(skipped).id());

        latest(agent)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.check.id").value(manual.id()))
                .andExpect(jsonPath("$.check.acceptedCandidates").value(1));
    }

    @Test
    @DisplayName("고른 살펴보기의 점검 대화를 지우면 check 가 null 이다")
    void deletedCheckConversationHidesCheck() throws Exception {
        Agent agent = agent();
        Conversation conversation = conversation(agent);
        ProactiveCheck check = succeeded(agent, conversation, CheckTrigger.MANUAL);
        accepted(check, conversation, "합성 문제");
        transactions.executeWithoutResult(status -> conversations.deleteIfActive(conversation.id(), admin.id(), now()));

        latest(agent).andExpect(status().isOk()).andExpect(jsonPath("$.check", nullValue()));
    }

    @Test
    @DisplayName("MEMBER 역할이 두 경로를 부르면 403 FORBIDDEN 이다")
    void memberIsForbidden() throws Exception {
        Agent agent = agent();
        Conversation conversation = conversation(agent);
        ProactiveCheck check = succeeded(agent, conversation, CheckTrigger.MANUAL);
        accepted(check, conversation, "합성 문제");
        when(currentUser.require()).thenReturn(member);

        latest(agent)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        run(check)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertThat(evaluations.findFirstByUserIdAndCheckIdOrderByIdDesc(member.id(), check.id()))
                .as("거절된 요청은 평가를 남기지 않는다")
                .isEmpty();
    }

    @Test
    @DisplayName("같은 평가를 두 번 판정하면 읽기는 뒤 묶음만 준다")
    void readsOnlyLastDecisionBatch() throws Exception {
        Agent agent = agent();
        Conversation conversation = conversation(agent);
        ProactiveCheck check = succeeded(agent, conversation, CheckTrigger.MANUAL);
        accepted(check, conversation, "합성 문제");
        run(check).andExpect(status().isCreated());
        Long evaluationId = evaluations
                .findFirstByUserIdAndCheckIdOrderByIdDesc(admin.id(), check.id())
                .orElseThrow()
                .id();
        var second = autonomy.decide(admin, evaluationId);
        assertThat(decisions.findByUserIdAndEvaluationIdInOrderByIdAsc(admin.id(), List.of(evaluationId)))
                .as("두 번 판정해 두 묶음이 쌓였다")
                .hasSize(2);

        latest(agent)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decisions", hasSize(1)))
                .andExpect(jsonPath("$.decisions[0].id").value(second.getFirst().id()));
    }

    @Test
    @DisplayName("다른 사용자의 살펴보기에 판정을 요청하면 404 VALUE_EVALUATION_NOT_FOUND 이고 평가를 남기지 않는다")
    void othersCheckIsNotFound() throws Exception {
        Agent agent = agent();
        Conversation othersConversation = conversationOf(other, agent);
        ProactiveCheck othersCheck = checkOf(other, agent, othersConversation, CheckTrigger.MANUAL);
        accepted(othersCheck, othersConversation, "합성 문제");

        run(othersCheck)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("VALUE_EVALUATION_NOT_FOUND"));
        assertThat(evaluations.findFirstByUserIdAndCheckIdOrderByIdDesc(admin.id(), othersCheck.id()))
                .isEmpty();
    }

    private ResultActions latest(Agent agent) throws Exception {
        return mvc.perform(get("/api/v1/admin/agents/{code}/value-evaluation", agent.code()));
    }

    private ResultActions run(ProactiveCheck check) throws Exception {
        return mvc.perform(post("/api/v1/admin/proactive-checks/{checkId}/value-evaluation-runs", check.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY));
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    private Agent agent() {
        String code = "overview-" + UUID.randomUUID();
        Agent saved = agents.save(Agent.of(
                code,
                "커리어",
                code,
                "http://agent-runtime.test/p/" + code,
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.GROUP,
                admin.id(),
                now()));
        createdAgents.add(saved.id());
        return saved;
    }

    private Conversation conversation(Agent agent) {
        return conversationOf(admin, agent);
    }

    private Conversation conversationOf(CurrentUser user, Agent agent) {
        Conversation saved = conversations.save(Conversation.startedForCheck(user.id(), "점검", agent.id(), now()));
        createdConversations.add(saved.id());
        return saved;
    }

    private ProactiveCheck succeeded(Agent agent, Conversation conversation, CheckTrigger trigger) {
        return checkOf(admin, agent, conversation, trigger);
    }

    private ProactiveCheck checkOf(CurrentUser user, Agent agent, Conversation conversation, CheckTrigger trigger) {
        Instant now = now();
        ProactiveCheck check = ProactiveCheck.started(user.id(), agent.id(), conversation.id(), trigger, false, now);
        check.succeed(CheckOutcome.FINDINGS, 1, 0, null, 0, 0, 0, 0, 0, now);
        ProactiveCheck saved = checks.save(check);
        createdChecks.add(saved.id());
        return saved;
    }

    private void accepted(ProactiveCheck check, Conversation conversation, String problem) {
        Instant now = now();
        String key = "study:" + UUID.randomUUID();
        problems.save(ProactiveCheckProblem.of(
                check.id(),
                conversation.id(),
                ProblemStatus.ACCEPTED,
                null,
                key,
                problem,
                "합성 목표",
                "ACTION",
                "합성 행동",
                "HIGH",
                "합성 효과",
                "NONE",
                null,
                null,
                List.of(new ProblemEvidence(key, "https://docs.example.com/" + key, now.minusSeconds(60))),
                now.minusSeconds(60),
                now));
    }
}
