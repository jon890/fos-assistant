package com.bifos.assistant.proactive;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.application.AgentConnectorBindings;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.application.CheckConversations;
import com.bifos.assistant.hermes.HermesRunsClient;
import com.bifos.assistant.hermes.HermesSkillClient;
import com.bifos.assistant.hermes.HermesSkillClient.HermesSkill;
import com.bifos.assistant.hermes.HermesToolsetClient;
import com.bifos.assistant.hermes.StubHermesRunsClient;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.hermes.dto.TokenUsage;
import com.bifos.assistant.proactive.application.ProactiveCheckService;
import com.bifos.assistant.proactive.domain.ProactiveCheck;
import com.bifos.assistant.proactive.domain.ProactiveLoopRun;
import com.bifos.assistant.proactive.domain.ProactiveLoopSetting;
import com.bifos.assistant.proactive.domain.type.CheckOutcome;
import com.bifos.assistant.proactive.domain.type.CheckTrigger;
import com.bifos.assistant.proactive.infra.ProactiveCheckRepository;
import com.bifos.assistant.proactive.infra.ProactiveLoopRunRepository;
import com.bifos.assistant.proactive.infra.ProactiveLoopSettingRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.TestClock;
import com.bifos.assistant.testsupport.TrackingBackgroundTasks;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 매일 루프 통합 검사가 함께 쓰는 준비와 정리다. 설치 설정은 검사 클래스 단위로만 바꿀 수 있어, 설정 조합마다 검사 클래스를 나누고 이 클래스를
 * 이어받는다.
 *
 * <p>매일 깨우기는 {@link ProactiveCheckService#startScheduled} 를 직접 부르고, 대역 Hermes 가 버전 3 결과 블록을 답한다. 깨운 뒤에는
 * {@link TrackingBackgroundTasks#awaitIdle} 로 루프까지 끝나기를 기다린다. 모든 값은 합성이다.
 */
abstract class ProactiveLoopTestSupport {

    static final Instant BASE = Instant.parse("2026-11-02T00:00:00Z");
    static final String CANDIDATE_KEY = "career:deadline-tomorrow";
    private static final String FINDING_KEY = "position:example-deadline";
    private static final Duration IDLE_LIMIT = Duration.ofSeconds(20);

    @Autowired
    ProactiveCheckService checkService;

    @Autowired
    TrackingBackgroundTasks backgroundTasks;

    @Autowired
    HermesRunsClient hermes;

    @Autowired
    TestClock clock;

    @Autowired
    AppUserRepository users;

    @Autowired
    AgentRepository agents;

    @Autowired
    CheckConversations checkConversations;

    @Autowired
    ProactiveCheckRepository checks;

    @Autowired
    ProactiveLoopRunRepository loopRuns;

    @Autowired
    ProactiveLoopSettingRepository loopSettings;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    HermesToolsetClient toolsets;

    @Autowired
    HermesSkillClient skillClient;

    @Autowired
    AgentConnectorBindings connectorBindings;

    private final List<Long> createdUsers = new ArrayList<>();

    @BeforeEach
    void setUp() {
        stub().reset();
        clock.set(BASE);
        when(toolsets.readEnabled(anyString(), anyString())).thenReturn(List.of("web", "skills", "fos-assistant"));
        when(skillClient.list(anyString())).thenReturn(List.of(new HermesSkill("proactive-check", "살펴보기", true)));
        doReturn(Set.of()).when(connectorBindings).connectorServers(any());
    }

    @AfterEach
    void tearDown() {
        for (Long userId : createdUsers) {
            jdbc.update("DELETE FROM proactive_loop_run WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM proactive_loop_setting WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM decision_feedback_event WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM proactive_autonomy_decision WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM user_autonomy_preference WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM proactive_value_evaluation WHERE user_id = ?", userId);
            jdbc.update(
                    "DELETE FROM proactive_check_problem WHERE check_id IN (SELECT id FROM proactive_check WHERE user_id = ?)",
                    userId);
            jdbc.update(
                    "DELETE FROM proactive_check_finding WHERE check_id IN (SELECT id FROM proactive_check WHERE user_id = ?)",
                    userId);
            jdbc.update("DELETE FROM proactive_check WHERE user_id = ?", userId);
            jdbc.update(
                    "DELETE FROM execution_event WHERE execution_id IN (SELECT id FROM agent_execution WHERE user_id = ?)",
                    userId);
            jdbc.update("DELETE FROM agent_execution WHERE user_id = ?", userId);
            jdbc.update(
                    "DELETE FROM chat_message WHERE conversation_id IN (SELECT id FROM conversation WHERE user_id = ?)",
                    userId);
            jdbc.update("DELETE FROM conversation WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM agent WHERE owner_user_id = ?", userId);
            jdbc.update("DELETE FROM app_user WHERE id = ?", userId);
        }
        createdUsers.clear();
    }

    CurrentUser newUser() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        AppUser user =
                users.save(AppUser.of("loop-" + suffix + "@example.com", "사용자A", 1L, UserRole.MEMBER, clock.instant()));
        createdUsers.add(user.id());
        return new CurrentUser(user.id(), user.email(), user.displayName(), user.groupId(), user.role());
    }

    Agent newAgent(CurrentUser user) {
        String code = "loop-" + UUID.randomUUID().toString().substring(0, 8);
        return agents.save(Agent.of(
                code,
                "커리어",
                code,
                "http://agent-runtime.test/p/" + code,
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE,
                user.id(),
                clock.instant()));
    }

    /** 설정 줄을 서비스를 거치지 않고 저장한다. 설치 설정이 꺼져 있어도 켜진 줄을 둘 수 있다. */
    void saveSetting(CurrentUser user, Agent agent, boolean enabled, Instant snoozedUntil) {
        loopSettings.save(ProactiveLoopSetting.of(user.id(), agent.id(), enabled, snoozedUntil, clock.instant()));
    }

    /**
     * 이번 깨우기와 다른 매일 깨우기 살펴보기와 그 시도 줄을 그 시각으로 저장한다. 하루 상한을 셀 앞선 시도다.
     *
     * @param at 앞선 시도를 저장한 시각
     */
    ProactiveLoopRun saveEarlierDecidedRun(CurrentUser user, Agent agent, Instant at) {
        Long conversationId =
                checkConversations.findOrCreate(user, agent).conversation().id();
        ProactiveCheck earlier =
                ProactiveCheck.started(user.id(), agent.id(), conversationId, CheckTrigger.SCHEDULED, false, at);
        earlier.succeed(CheckOutcome.NOTHING_NEW, 0, 0, null, 0, 0, 0, 0, 0, at);
        earlier = checks.save(earlier);
        ProactiveLoopRun run = ProactiveLoopRun.running(user.id(), earlier.id(), at);
        run.decided(null, at);
        return loopRuns.save(run);
    }

    /** 매일 깨우기 한 번을 돌리고 루프까지 끝나기를 기다린다. 이번 살펴보기 번호를 돌려준다. */
    Long wake(CurrentUser user, Agent agent, String output) throws InterruptedException {
        stub().willAnswer(command -> answer(output));
        AtomicReference<Long> started = new AtomicReference<>();
        checkService.startScheduled(user, agent.code(), saved -> started.set(saved.id()));
        backgroundTasks.awaitIdle(IDLE_LIMIT);
        return started.get();
    }

    /** 단추로 연 살펴보기 한 번을 돌리고 끝나기를 기다린다. */
    void startManual(CurrentUser user, Agent agent, String output) throws InterruptedException {
        stub().willAnswer(command -> answer(output));
        checkService.start(user, agent.code(), CheckTrigger.MANUAL);
        backgroundTasks.awaitIdle(IDLE_LIMIT);
    }

    void awaitIdle() throws InterruptedException {
        backgroundTasks.awaitIdle(IDLE_LIMIT);
    }

    /** 받아들일 문제 후보 하나와 그 근거 발견을 담은 버전 3 결과 블록이다. */
    String candidateOutput() {
        String now = clock.instant().toString();
        return block("{\"version\":3,\"outcome\":\"FINDINGS\",\"findings\":[{"
                + "\"area\":\"position\",\"topicKey\":\"" + FINDING_KEY + "\",\"title\":\"예시 회사 지원 마감 하루 전\","
                + "\"sourceUrl\":\"https://example.com/position/example-deadline\",\"checkedAt\":\"" + now + "\","
                + "\"freshness\":\"CURRENT\",\"whyItMatters\":\"합성 fixture 의 발견이다\","
                + "\"facts\":[\"합성 사실\"],\"next\":{\"type\":\"QUESTION\",\"text\":\"살펴볼까요\"}}],"
                + "\"problemCandidates\":[{\"problemKey\":\"" + CANDIDATE_KEY + "\","
                + "\"problem\":\"관심 포지션의 지원 마감이 내일인데 공고 요건을 아직 정리하지 않았다\","
                + "\"relatedGoal\":\"이번 분기 이직 준비\",\"confidence\":\"HIGH\","
                + "\"expectedBenefit\":\"마감 전에 지원 여부를 정한다\",\"sideEffect\":\"NONE\","
                + "\"evidence\":[\"" + FINDING_KEY + "\"],"
                + "\"proposedAction\":{\"type\":\"ACTION\",\"text\":\"공고 요건을 읽고 준비 상태를 정리한다\"}}],"
                + "\"report\":{\"changed\":[\"합성 변화\"],\"done\":[\"합성 확인\"],\"next\":[\"합성 다음\"]}}");
    }

    /** 새 것이 없다는 버전 3 결과 블록이다. 문제 후보가 없다. */
    static String nothingNewOutput() {
        return block("{\"version\":3,\"outcome\":\"NOTHING_NEW\"}");
    }

    List<ProactiveLoopRun> runsOf(CurrentUser user) {
        return loopRuns.findAll().stream()
                .filter(run -> run.userId().equals(user.id()))
                .toList();
    }

    long evaluationCount(CurrentUser user) {
        return count("SELECT COUNT(*) FROM proactive_value_evaluation WHERE user_id = ?", user.id());
    }

    long decisionCount(CurrentUser user) {
        return count("SELECT COUNT(*) FROM proactive_autonomy_decision WHERE user_id = ?", user.id());
    }

    long notificationCount(CurrentUser user) {
        return count("SELECT COUNT(*) FROM notification WHERE user_id = ?", user.id());
    }

    private long count(String sql, Long userId) {
        Long count = jdbc.queryForObject(sql, Long.class, userId);
        return count == null ? 0 : count;
    }

    private static String block(String json) {
        return "블록 밖의 글\n<fos-check-result>\n" + json + "\n</fos-check-result>";
    }

    private static HermesRunResult answer(String output) {
        return HermesRunResult.of(
                "run-" + UUID.randomUUID(), null, "completed", output, "model", "provider", TokenUsage.empty());
    }

    private StubHermesRunsClient stub() {
        return (StubHermesRunsClient) hermes;
    }
}
