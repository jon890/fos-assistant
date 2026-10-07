package com.bifos.assistant.proactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.application.AgentConnectorBindings;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.chat.application.ChatService;
import com.bifos.assistant.chat.application.TurnCancellation;
import com.bifos.assistant.chat.domain.ChatMessage;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.domain.type.MessageRole;
import com.bifos.assistant.chat.infra.ChatMessageRepository;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.hermes.HermesRunEventStream;
import com.bifos.assistant.hermes.HermesRunsClient;
import com.bifos.assistant.hermes.HermesSkillClient;
import com.bifos.assistant.hermes.HermesSkillClient.HermesSkill;
import com.bifos.assistant.hermes.HermesToolsetClient;
import com.bifos.assistant.hermes.StubHermesRunsClient;
import com.bifos.assistant.hermes.dto.HermesRunCommand;
import com.bifos.assistant.hermes.dto.HermesRunResult;
import com.bifos.assistant.hermes.dto.TokenUsage;
import com.bifos.assistant.memory.application.MemoryService;
import com.bifos.assistant.memory.domain.Memory;
import com.bifos.assistant.notification.infra.NotificationRepository;
import com.bifos.assistant.proactive.domain.CheckReport;
import com.bifos.assistant.proactive.domain.ProactiveCheck;
import com.bifos.assistant.proactive.domain.ProactiveCheckFinding;
import com.bifos.assistant.proactive.domain.type.CheckOutcome;
import com.bifos.assistant.proactive.domain.type.CheckSkippedReason;
import com.bifos.assistant.proactive.domain.type.CheckStatus;
import com.bifos.assistant.proactive.domain.type.CheckTrigger;
import com.bifos.assistant.proactive.domain.type.FindingReason;
import com.bifos.assistant.proactive.infra.ProactiveCheckFindingRepository;
import com.bifos.assistant.proactive.infra.ProactiveCheckRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.task.application.ProactiveScheduleService;
import com.bifos.assistant.task.application.TaskDispatcher;
import com.bifos.assistant.task.domain.TaskRun;
import com.bifos.assistant.task.domain.type.TaskRunReason;
import com.bifos.assistant.task.domain.type.TaskRunStatus;
import com.bifos.assistant.task.infra.TaskRepository;
import com.bifos.assistant.task.infra.TaskRunRepository;
import com.bifos.assistant.task.infra.TaskTriggerRepository;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 커리어 에이전트의 매일 깨우기를 시계를 앞으로 돌려 여러 날 이어 돌린다. 이슈 #166 의 파일럿 검증이다.
 *
 * <p>하루마다 발화기와 시작 단계({@link TaskDispatcher#tick})를 그날 예정 시각 뒤로 부르고, 대역 Hermes 가 그날의 결과 블록을 답한다.
 * 대역은 모델이 아니다. 무엇을 조사할지는 분야 지침과 실제 모델의 몫이고, 여기서는 Control Plane 이 발화, 맥락, 억제, 보고, 피드백 반영을
 * 하루씩 이어 지키는지를 본다. 붙은 커넥터의 직접 호출과 읽기 경계는 e2e 의 「먼저 살펴보기」 시나리오가 본다.
 *
 * <p>날마다의 흐름은 이렇다. 모든 글과 주소는 합성 값이다.
 *
 * <ol>
 *   <li>새 포지션을 찾아 답과 보고를 남긴다
 *   <li>보고를 열지 않아 모델 없이 건너뛴다. 사용자가 보고를 열고 「이런 공고는 관심 없어」 라고 답하고 Memory 제안이 생긴다
 *   <li>사용자 답이 변화 신호에 실린다. 모델이 같은 공고를 다시 내도 이미 알린 것이라 답과 보고 없이 침묵한다
 *   <li>사용자가 Memory 제안을 받아들인 뒤 Memory 문맥이 바뀌었다고 실린다. 새 근거가 없어 침묵한다
 *   <li>커리어 출처를 읽지 못해 장애를 무소식과 구분해 남긴다
 *   <li>사용자가 깨우기를 끄면 발화하지 않는다
 * </ol>
 */
@SpringBootTest(properties = {"hermes.run-timeout=30s", "assistant.proactive-check.max-duration=20s"})
@ActiveProfiles("test")
@Import(CareerDailyPilotTest.Runtime.class)
class CareerDailyPilotTest {

    private static final Duration WAIT_LIMIT = Duration.ofSeconds(10);
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    /** 깨우기를 켜는 때다. 서울 시각 11월 1일 09:00 이라 첫 발화는 11월 2일 08:30 이다. */
    private static final Instant ENABLED_AT = Instant.parse("2026-11-01T00:00:00Z");

    private static final Instant FIRST_WAKE = Instant.parse("2026-11-01T23:30:00Z");
    private static final PilotClock CLOCK = new PilotClock(ENABLED_AT);

    private static final String POSITION_KEY = "position:example-backend-platform";
    private static final String POSITION_URL = "https://jobs.example.com/backend-platform";
    private static final String FEEDBACK = "이런 공고는 관심 없어";
    private static final String SOURCE_FAILURE = "mcp__career__get_context_document CAREER_UNAVAILABLE";

    @TestConfiguration
    static class Runtime {
        @Bean
        @Primary
        StubHermesRunsClient pilotStubHermesRunsClient() {
            return new StubHermesRunsClient();
        }

        @Bean
        @Primary
        Clock pilotClock() {
            return CLOCK;
        }
    }

    @Autowired
    TaskDispatcher dispatcher;

    @Autowired
    ProactiveScheduleService schedules;

    @Autowired
    ChatService chat;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    MemoryService memories;

    @Autowired
    HermesRunsClient hermes;

    @Autowired
    TurnCancellation turns;

    @Autowired
    AppUserRepository users;

    @Autowired
    AgentRepository agents;

    @Autowired
    TaskRepository tasks;

    @Autowired
    TaskTriggerRepository triggers;

    @Autowired
    TaskRunRepository runs;

    @Autowired
    ProactiveCheckRepository checks;

    @Autowired
    ProactiveCheckFindingRepository findings;

    @Autowired
    ChatMessageRepository messages;

    @Autowired
    NotificationRepository notifications;

    @MockitoBean
    HermesToolsetClient toolsets;

    @MockitoBean
    HermesSkillClient skillClient;

    /** 커리어 커넥터 서버가 붙은 에이전트로 둔다. 붙은 연결이 있으면 지시가 직접 호출을 고른다. */
    @MockitoBean
    AgentConnectorBindings connectorBindings;

    @MockitoBean
    HermesRunEventStream eventStream;

    private CurrentUser owner;
    private Agent agent;
    private final List<Long> createdChecks = new ArrayList<>();

    private StubHermesRunsClient stub() {
        return (StubHermesRunsClient) hermes;
    }

    @BeforeEach
    void setUp() {
        CLOCK.set(ENABLED_AT);
        stub().reset();
        cleanTasks();
        when(toolsets.readEnabled(anyString(), anyString())).thenReturn(List.of("web", "skills", "fos-assistant"));
        when(skillClient.list(anyString())).thenReturn(List.of(new HermesSkill("proactive-check", "살펴보기", true)));
        when(connectorBindings.connectorServers(any())).thenReturn(Set.of("career"));
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        AppUser user =
                users.save(AppUser.of("pilot-" + suffix + "@example.com", "사용자A", 1L, UserRole.MEMBER, ENABLED_AT));
        owner = new CurrentUser(user.id(), user.email(), user.displayName(), user.groupId(), user.role());
        String code = "career-" + suffix;
        agent = agents.save(Agent.of(
                code,
                "커리어",
                code,
                "http://agent-runtime.test/p/" + code,
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE,
                owner.id(),
                ENABLED_AT));
    }

    @AfterEach
    void tearDown() {
        createdChecks.addAll(checksOfAgent().stream().map(ProactiveCheck::id).toList());
        findings.deleteAll(findings.findAll().stream()
                .filter(finding -> createdChecks.contains(finding.checkId()))
                .toList());
        cleanTasks();
    }

    @Test
    @DisplayName("매일 깨우기가 엿새 동안 발견, 건너뛰기, 피드백 뒤 되풀이 침묵, 정상 침묵, 출처 장애, 끄기를 차례로 지킨다")
    void runsCareerDailyCycleAcrossSixDays() {
        assertThat(schedules
                        .update(owner, agent.code(), true, "08:30", SEOUL.getId())
                        .nextRunAt())
                .as("첫 발화 예정 시각")
                .isEqualTo(FIRST_WAKE);

        // 1일: 새 포지션을 찾는다.
        answerWith(command -> result(positionFindings(null)));
        ProactiveCheck first = wake(1);
        assertThat(first.newFindings()).as("1일 새 발견").isEqualTo(1);
        assertThat(first.report()).as("1일 보고").isNotNull();
        assertThat(first.report().evidence()).containsExactly(POSITION_URL);
        HermesRunCommand firstCommand = stub().received().getLast();
        assertThat(firstCommand.input()).contains("- 지난 살펴보기: 처음").contains("최근에 알린 발견이 없다.");
        assertThat(firstCommand.instructions())
                .as("붙은 커리어 커넥터를 직접 부르고 읽기만 하라는 지시")
                .contains("- 연결한 서비스의 도구는 직접 부른다.")
                .contains("- 이번 실행은 읽기만 한다.");
        ChatMessage discovery = lastMessage(first);
        assertThat(discovery.role()).isEqualTo(MessageRole.ASSISTANT);
        assertThat(discovery.content()).contains("**새로 알릴 것**").contains("](" + POSITION_URL + ")");
        assertThat(messagesOf(first))
                .as("예약 실행은 시작 알림 줄을 남기지 않는다")
                .extracting(ChatMessage::role)
                .containsExactly(MessageRole.ASSISTANT);
        assertThat(runOf(1).status()).isEqualTo(TaskRunStatus.SUCCEEDED);

        // 2일: 보고를 열지 않았으므로 모델을 부르지 않는다.
        int submittedBefore = stub().received().size();
        ProactiveCheck second = wake(2);
        assertThat(second.skippedReason()).isEqualTo(CheckSkippedReason.UNREAD_REPORT);
        assertThat(stub().received()).as("2일 Hermes 제출").hasSize(submittedBefore);
        assertThat(runOf(2).status()).isEqualTo(TaskRunStatus.SKIPPED);
        assertThat(runOf(2).reason()).isEqualTo(TaskRunReason.UNREAD_REPORT);

        // 사용자가 지금 화면의 단추 대신 점검 대화를 직접 열고 거절한다. 그 말에서 나온 영구 선호는 제안으로만 남는다.
        CLOCK.set(CLOCK.instant().plus(Duration.ofHours(10)));
        chat.history(owner, first.conversationId());
        assertThat(checks.findById(first.id()).orElseThrow().reportOpenedAt())
                .as("점검 대화를 읽은 시각")
                .isEqualTo(CLOCK.instant());
        messages.save(ChatMessage.fromUser(first.conversationId(), owner.id(), FEEDBACK, CLOCK.instant()));
        Memory proposal = memories.proposeUser(owner, "커리어 선호", "백엔드 플랫폼 공고는 원하지 않는다", null);

        // 3일: 사용자 답이 실리고, 모델이 같은 공고를 다시 내도 침묵한다.
        answerWith(command -> result(positionFindings(null)));
        ProactiveCheck third = wake(3);
        HermesRunCommand thirdCommand = stub().received().getLast();
        assertThat(thirdCommand.input())
                .as("건너뛴 2일이 아니라 1일 살펴보기를 기준으로 본 변화 신호")
                .contains("- 지난 살펴보기: " + first.finishedAt())
                .contains("- 그 뒤 사용자가 이 대화에 보낸 메시지: 1개")
                .contains("- Memory 문맥이 지난 살펴보기와 같은지: 같음")
                .contains("[position] " + POSITION_KEY)
                .contains("그 뒤 사용자 메시지 1개");
        assertThat(thirdCommand.sessionId()).as("같은 점검 대화 session").isEqualTo(firstCommand.sessionId());
        assertThat(third.newFindings()).isZero();
        assertThat(third.referenceFindings()).isEqualTo(1);
        assertThat(third.report()).as("되풀이만 있으면 보고를 만들지 않는다").isNull();
        assertThat(findingsOf(third)).extracting(ProactiveCheckFinding::reason).containsExactly(FindingReason.REPEATED);
        assertThat(lastMessage(third).content()).as("3일 뒤 마지막 메시지").isEqualTo(FEEDBACK);

        // 사용자가 Memory 제안을 받아들인다.
        memories.accept(owner, proposal.id());

        // 4일: Memory 문맥이 바뀌었다고 실리고, 새 근거가 없어 침묵한다. 3일의 침묵이 4일을 막지 않는다.
        answerWith(command -> result("{\"version\":2,\"outcome\":\"NOTHING_NEW\"}"));
        ProactiveCheck fourth = wake(4);
        assertThat(fourth.skippedReason()).as("4일은 모델을 부른다").isNull();
        assertThat(stub().received().getLast().input())
                .contains("- 지난 살펴보기: " + third.finishedAt())
                .contains("- 그 뒤 사용자가 이 대화에 보낸 메시지: 0개")
                .contains("- Memory 문맥이 지난 살펴보기와 같은지: 바뀜");
        assertThat(fourth.report()).isNull();
        assertThat(lastMessage(fourth).content()).isEqualTo(FEEDBACK);

        // 5일: 커리어 출처 장애는 무소식으로 숨기지 않는다.
        answerWith(command ->
                result("{\"version\":2,\"outcome\":\"NOTHING_NEW\",\"sourceFailures\":[\"" + SOURCE_FAILURE + "\"]}"));
        ProactiveCheck fifth = wake(5);
        ChatMessage failure = lastMessage(fifth);
        assertThat(failure.role()).isEqualTo(MessageRole.ASSISTANT);
        assertThat(failure.content()).contains("**확인하지 못한 출처**").contains("CAREER\\_UNAVAILABLE");
        assertThat(fifth.report()).as("장애를 알린 보고").isNotNull();

        // 6일: 사용자가 끄면 발화도 Hermes 제출도 없다.
        schedules.update(owner, agent.code(), false, "08:30", SEOUL.getId());
        int submittedBeforeOff = stub().received().size();
        tickAt(FIRST_WAKE.plus(Duration.ofDays(5)).plusSeconds(30));
        assertThat(runsOfAgent()).as("끈 뒤 발화 줄").hasSize(5);
        assertThat(stub().received()).hasSize(submittedBeforeOff);

        // 지표: 깨우기 5번, 모델 4번, 메시지 2번, 침묵 2번, 건너뛰기 1번, 되풀이 억제 1번, 출처 장애 1번.
        List<ProactiveCheck> all = checksOfAgent();
        assertThat(runsOfAgent()).as("wake count").hasSize(5);
        assertThat(all.stream().filter(check -> check.skippedReason() == null))
                .as("모델을 부른 깨우기")
                .hasSize(4);
        assertThat(all.stream().filter(check -> check.report() != null))
                .as("message surfaced count")
                .hasSize(2);
        assertThat(all.stream()
                        .filter(check -> check.skippedReason() == null
                                && check.status() == CheckStatus.SUCCEEDED
                                && check.report() == null))
                .as("silence count")
                .hasSize(2);
        assertThat(all.stream().filter(check -> check.referenceFindings() > 0 && check.report() == null))
                .as("되풀이를 억제한 깨우기")
                .hasSize(1);
        assertThat(messagesOf(first).stream().filter(message -> message.role() == MessageRole.ASSISTANT))
                .as("점검 대화의 답")
                .hasSize(2);
        assertThat(notifications.findByUserIdOrderByCreatedAtDescIdDesc(owner.id(), PageRequest.of(0, 10)))
                .as("깨우기는 실행 알림을 만들지 않는다")
                .isEmpty();
    }

    @Test
    @DisplayName("점검 대화를 읽거나 그 대화에 보내면 그 사용자의 그 대화 보고만 연 것으로 적는다")
    void marksOnlyReportsOfReadCheckConversationAsOpened() {
        Conversation read =
                conversations.save(Conversation.startedForCheck(owner.id(), "먼저 살펴보기 · 커리어", agent.id(), ENABLED_AT));
        Agent otherAgent = agents.save(Agent.of(
                agent.code() + "-b",
                "공부",
                agent.code() + "-b",
                "http://agent-runtime.test/p/" + agent.code() + "-b",
                CostMode.SUBSCRIPTION,
                CredentialScope.SHARED_HOUSEHOLD,
                AgentVisibility.PRIVATE,
                owner.id(),
                ENABLED_AT));
        Conversation otherConversation = conversations.save(
                Conversation.startedForCheck(owner.id(), "먼저 살펴보기 · 공부", otherAgent.id(), ENABLED_AT));
        AppUser otherUser = users.save(AppUser.of(
                "pilot-other-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com",
                "사용자B",
                1L,
                UserRole.MEMBER,
                ENABLED_AT));
        Conversation otherUsers = conversations.save(
                Conversation.startedForCheck(otherUser.id(), "먼저 살펴보기 · 커리어", agent.id(), ENABLED_AT));
        ProactiveCheck mine = reported(owner.id(), agent.id(), read.id());
        ProactiveCheck mineEarlier = reported(owner.id(), agent.id(), read.id());
        ProactiveCheck otherConversationReport = reported(owner.id(), otherAgent.id(), otherConversation.id());
        ProactiveCheck otherUserReport = reported(otherUser.id(), agent.id(), otherUsers.id());
        Instant readAt = ENABLED_AT.plus(Duration.ofHours(2));
        CLOCK.set(readAt);

        chat.history(owner, read.id());

        assertThat(checks.findById(mine.id()).orElseThrow().reportOpenedAt()).isEqualTo(readAt);
        assertThat(checks.findById(mineEarlier.id()).orElseThrow().reportOpenedAt())
                .isEqualTo(readAt);
        assertThat(checks.findById(otherConversationReport.id()).orElseThrow().reportOpenedAt())
                .as("같은 사용자의 다른 점검 대화")
                .isNull();
        assertThat(checks.findById(otherUserReport.id()).orElseThrow().reportOpenedAt())
                .as("다른 사용자의 점검 대화")
                .isNull();

        CLOCK.set(readAt.plusSeconds(60));
        chat.history(owner, read.id());
        assertThat(checks.findById(mine.id()).orElseThrow().reportOpenedAt())
                .as("다시 읽어도 첫 시각")
                .isEqualTo(readAt);
    }

    /** 끝난 살펴보기에 보고 하나를 붙여 저장한다. 정리 단계가 지운다. */
    private ProactiveCheck reported(Long userId, Long agentId, Long conversationId) {
        ProactiveCheck check =
                ProactiveCheck.started(userId, agentId, conversationId, CheckTrigger.SCHEDULED, false, ENABLED_AT);
        check.succeed(
                CheckOutcome.FINDINGS,
                1,
                0,
                new CheckReport(List.of("새 포지션"), List.of(), List.of(POSITION_URL), List.of(), List.of()),
                0,
                0,
                0,
                0,
                0,
                ENABLED_AT.plusSeconds(60));
        ProactiveCheck saved = checks.save(check);
        createdChecks.add(saved.id());
        return saved;
    }

    /** 그날 예정 시각 30초 뒤에 깨우고, 살펴보기가 끝나면 1분 뒤 다시 돌려 발화 줄에 결과를 회복한다. */
    private ProactiveCheck wake(int day) {
        Instant at = FIRST_WAKE.plus(Duration.ofDays(day - 1L)).plusSeconds(30);
        int before = checksOfAgent().size();
        tickAt(at);
        ProactiveCheck check = awaitNewCheck(before);
        tickAt(at.plus(Duration.ofMinutes(1)));
        return checks.findById(check.id()).orElseThrow();
    }

    private void tickAt(Instant at) {
        CLOCK.set(at);
        dispatcher.tick(at);
    }

    private ProactiveCheck awaitNewCheck(int before) {
        long deadline = System.nanoTime() + WAIT_LIMIT.toNanos();
        while (true) {
            List<ProactiveCheck> all = checksOfAgent();
            if (all.size() > before) {
                ProactiveCheck latest = all.getLast();
                boolean idle = !turns.markOf(latest.conversationId()).running();
                if (latest.status() != CheckStatus.RUNNING && idle) {
                    return latest;
                }
            }
            if (System.nanoTime() > deadline) {
                fail("새 살펴보기가 %s 안에 끝나지 않았다", WAIT_LIMIT);
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                fail("기다리는 중에 끊겼다");
            }
        }
    }

    private void answerWith(Function<HermesRunCommand, HermesRunResult> answer) {
        stub().willAnswer(answer);
    }

    private TaskRun runOf(int day) {
        Instant scheduled = FIRST_WAKE.plus(Duration.ofDays(day - 1L));
        return runsOfAgent().stream()
                .filter(run -> run.scheduledFor().equals(scheduled))
                .findFirst()
                .orElseThrow();
    }

    private List<TaskRun> runsOfAgent() {
        return runs.findAll().stream()
                .filter(run -> run.ownerUserId().equals(owner.id()))
                .sorted(Comparator.comparing(TaskRun::id))
                .toList();
    }

    private List<ProactiveCheck> checksOfAgent() {
        if (agent == null) {
            return List.of();
        }
        return checks.findAll().stream()
                .filter(check -> check.agentId().equals(agent.id()))
                .sorted(Comparator.comparing(ProactiveCheck::id))
                .toList();
    }

    private List<ProactiveCheckFinding> findingsOf(ProactiveCheck check) {
        return findings.findAll().stream()
                .filter(finding -> finding.checkId().equals(check.id()))
                .toList();
    }

    private List<ChatMessage> messagesOf(ProactiveCheck check) {
        return messages.findByConversationIdOrderByIdAsc(check.conversationId());
    }

    private ChatMessage lastMessage(ProactiveCheck check) {
        return messagesOf(check).getLast();
    }

    /** 확인 시각을 지금으로 둔 포지션 발견 하나와 v2 보고 초안이다. */
    private static String positionFindings(String changeSinceLast) {
        String change = changeSinceLast == null ? "" : ",\"changeSinceLast\":\"" + changeSinceLast + "\"";
        return "{\"version\":2,\"outcome\":\"FINDINGS\",\"summary\":\"새 포지션을 찾았어요\",\"findings\":[{"
                + "\"area\":\"position\",\"topicKey\":\"" + POSITION_KEY + "\",\"title\":\"예시 회사 백엔드 플랫폼\","
                + "\"sourceUrl\":\"" + POSITION_URL + "\",\"checkedAt\":\"" + CLOCK.instant() + "\","
                + "\"freshness\":\"CURRENT\",\"whyItMatters\":\"선호한 플랫폼 역할과 맞아요\","
                + "\"facts\":[\"공고가 열려 있다\"],\"next\":{\"type\":\"QUESTION\",\"text\":\"지원을 검토해 볼까요\"}"
                + change + "}],\"report\":{\"changed\":[\"새 포지션 하나\"],\"done\":[\"공고 원문을 확인했다\"],"
                + "\"next\":[\"지원 여부를 정한다\"]}}";
    }

    private static HermesRunResult result(String json) {
        return HermesRunResult.of(
                "run-" + UUID.randomUUID(),
                null,
                "completed",
                "블록 밖의 글\n<fos-check-result>\n" + json + "\n</fos-check-result>",
                "model",
                "provider",
                TokenUsage.empty());
    }

    private void cleanTasks() {
        runs.deleteAll();
        triggers.deleteAll();
        tasks.deleteAll();
        checks.deleteAllById(createdChecks);
        createdChecks.clear();
    }

    /** 검사가 정한 시각만 주는 시계다. 깨우기, 살펴보기, 메시지가 모두 이 시각을 쓴다. */
    static final class PilotClock extends Clock {
        private volatile Instant now;

        PilotClock(Instant now) {
            this.now = now;
        }

        void set(Instant instant) {
            now = instant;
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
