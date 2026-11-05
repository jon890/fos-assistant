package com.bifos.assistant.proactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.connector.connection.application.ConnectorEventWatches;
import com.bifos.assistant.connector.connection.domain.ConnectorBinding;
import com.bifos.assistant.connector.connection.domain.ConnectorConnection;
import com.bifos.assistant.connector.connection.domain.EventWatchState;
import com.bifos.assistant.connector.connection.domain.type.EventWatchResult;
import com.bifos.assistant.connector.connection.infra.ConnectorBindingRepository;
import com.bifos.assistant.connector.connection.infra.ConnectorConnectionRepository;
import com.bifos.assistant.hermes.StubHermesRunsClient;
import com.bifos.assistant.hermes.application.dto.ConnectorAppearance;
import com.bifos.assistant.hermes.application.dto.ConnectorCallError;
import com.bifos.assistant.hermes.application.dto.ConnectorEventListing;
import com.bifos.assistant.hermes.application.dto.ConnectorEventWatch;
import com.bifos.assistant.hermes.application.dto.ConnectorManifest;
import com.bifos.assistant.hermes.application.dto.ConnectorTool;
import com.bifos.assistant.hermes.application.dto.ConnectorUnknownErrors;
import com.bifos.assistant.hermes.application.dto.HermesRunCommand;
import com.bifos.assistant.hermes.application.dto.HermesRunResult;
import com.bifos.assistant.hermes.application.port.HermesConnectorClient;
import com.bifos.assistant.hermes.application.port.HermesEventFeedClient;
import com.bifos.assistant.hermes.domain.TokenUsage;
import com.bifos.assistant.proactive.check.domain.ProactiveCheck;
import com.bifos.assistant.proactive.check.domain.type.CheckSkippedReason;
import com.bifos.assistant.proactive.check.domain.type.CheckTrigger;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.skill.library.application.SkillCommandCatalog;
import com.bifos.assistant.task.application.EventWatchPoller;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.ConnectorCatalogTimes;
import com.bifos.assistant.testsupport.LongProactiveCheckTimeouts;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 사건 감지의 확인과 깨우기 계약이다(ADR-20261105 / event-watch). 요구사항은 {@code docs/features/proactive/spec.md} 의 REQ 다.
 *
 * <p>대시보드의 목록 조회와 카탈로그는 대역이고, 사건 실행은 대역 Hermes 가 답한다. 모든 값은 합성이다.
 */
@BackendIntegrationTest
@LongProactiveCheckTimeouts
class EventWatchPollerTest extends ProactiveTestSupport {

    private static final String CONNECTOR = "event-notes";
    private static final String LIST_TOOL = "list_notes";
    private static final String LINK = "https://notes.example.test/n/{id}";
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Autowired
    EventWatchPoller poller;

    @Autowired
    ConnectorEventWatches watches;

    @Autowired
    HermesEventFeedClient feed;

    @Autowired
    HermesConnectorClient connector;

    @Autowired
    ConnectorConnectionRepository connections;

    @Autowired
    ConnectorBindingRepository bindings;

    private CurrentUser user;
    private Agent agent;
    private ConnectorBinding binding;

    @Autowired
    SkillCommandCatalog skills;


    @BeforeEach
    void prepare() {
        reset(feed);
        doReturn(Set.of("proactive-check")).when(skills).enabledNames(any(Agent.class));
        stub().willAnswer(command -> answer(nothingNewOutput()));
        when(connector.readCatalog()).thenReturn(List.of(manifest("none")));
        ConnectorCatalogTimes.expire();
        user = newUser();
        agent = newAgent(user);
        binding = bind(agent);
        assertThat(watches.enable(user.id(), agent.id(), CONNECTOR, SEOUL, clock.instant()).checkpoint())
            .as("켤 때 지점은 켠 시각이다")
            .isEqualTo(clock.instant());
    }


    @AfterEach
    void unbind() {
        jdbc.update("DELETE FROM agent_connector_binding WHERE connection_id IN"
            + " (SELECT id FROM connector_connection WHERE user_id = ?)", user.id());
        jdbc.update("DELETE FROM connector_connection WHERE user_id = ?", user.id());
    }


    @Test
    @DisplayName("새 항목이 없는 확인은 모델 실행 줄과 살펴보기 줄 없이 상태 하나만 덮어쓴다 (REQ1)")
    void checkWithoutNewItemsRunsNoModel() throws InterruptedException {
        listing();
        clock.set(BASE.plus(Duration.ofMinutes(15)));

        poller.checkAll();
        awaitIdle();

        assertThat(count("SELECT COUNT(*) FROM proactive_check WHERE user_id = ?")).isZero();
        assertThat(count("SELECT COUNT(*) FROM agent_execution WHERE user_id = ?")).isZero();
        EventWatchState state = state();
        assertThat(state.lastResult()).isEqualTo(EventWatchResult.NO_NEW);
        assertThat(state.checkpoint()).isEqualTo(clock.instant());
        assertThat(state.runs()).as("모델 없이 끝난 확인은 하루 실행 수에 들지 않는다").isZero();
        // 겹쳐 받더라도 켠 시각 앞은 받지 않는다. 켜기 전에 온 메일로 깨우지 않는다.
        verify(feed).listEvents(eq(CONNECTOR), eq(agent.hermesProfile()), eq(BASE));
    }


    @Test
    @DisplayName("새 항목은 쓰기 허용과 상관없이 읽기 경계의 EVENT 살펴보기 하나로 깨우고, 입력에 식별자와 원문 링크를 감싸 싣는다 (REQ4, REQ8)")
    void newItemsWakeOneReadOnlyEventCheck() throws InterruptedException {
        agent.changeProactiveCheckWritesAllowed(true);
        agents.save(agent);
        listing("m1", "m2");

        ProactiveCheck check = wakeOnce();

        assertThat(check.trigger()).isEqualTo(CheckTrigger.EVENT);
        assertThat(check.writesAllowed()).as("사건 실행은 읽기 경계다").isFalse();
        HermesRunCommand command = stub().received().getFirst();
        assertThat(command.input()).contains("새 사건", "<external-data>", "m1 · https://notes.example.test/n/m1", "새 메모: 2건");
        assertThat(command.instructions()).contains("사건 실행 규칙", "이번 실행은 읽기만 한다");
        EventWatchState state = state();
        assertThat(state.lastResult()).isEqualTo(EventWatchResult.WOKE);
        assertThat(state.runs()).isEqualTo(1);
        assertThat(state.seen()).extracting(EventWatchState.Seen::id).containsExactlyInAnyOrder("m1", "m2");
    }


    @Test
    @DisplayName("같은 항목을 다시 받아도 다시 깨우지 않고, 저장된 상태만으로 다시 확인해도 같으며, 겹침 안에 늦게 온 항목은 깨운다 (REQ2)")
    void sameItemsNeverWakeTwiceAndLateItemsDo() throws InterruptedException {
        listing("m1");
        wakeOnce();
        clock.set(clock.instant().plus(Duration.ofMinutes(15)));

        // 서버가 다시 떠도 확인기는 바인딩 줄의 상태만 읽는다. 같은 항목이 겹침 구간에서 다시 와도 깨우지 않는다.
        poller.checkAll();
        awaitIdle();
        assertThat(count("SELECT COUNT(*) FROM proactive_check WHERE user_id = ?")).isEqualTo(1);
        assertThat(state().lastResult()).isEqualTo(EventWatchResult.NO_NEW);

        listing("m1", "late");
        clock.set(clock.instant().plus(Duration.ofMinutes(15)));
        poller.checkAll();
        awaitIdle();
        assertThat(count("SELECT COUNT(*) FROM proactive_check WHERE user_id = ?")).isEqualTo(2);
        assertThat(stub().received().getLast().input()).contains("late · ").doesNotContain("m1 · ");
    }


    @Test
    @DisplayName("점검 대화에 도는 turn 이 있으면 지점을 옮기지 않고 다음 확인에서 깨운다 (REQ12)")
    void busyConversationKeepsCheckpoint() throws InterruptedException {
        CountDownLatch release = new CountDownLatch(1);
        stub().beforeAwait(() -> await(release));
        stub().willAnswer(command -> answer(nothingNewOutput()));
        checkService.start(user, agent.code(), CheckTrigger.MANUAL);
        Instant before = state().checkpoint();
        listing("m1");
        clock.set(clock.instant().plus(Duration.ofMinutes(15)));

        poller.checkAll();

        assertThat(state().lastResult()).isEqualTo(EventWatchResult.BUSY);
        assertThat(state().checkpoint()).isEqualTo(before);
        assertThat(state().seen()).isEmpty();
        release.countDown();
        awaitIdle();
        stub().beforeAwait(() -> {
        });
        wakeOnce();
        assertThat(state().lastResult()).isEqualTo(EventWatchResult.WOKE);
    }


    @Test
    @DisplayName("바인딩의 하루 실행 상한에 닿으면 조회하지 않고 지점을 두며, 다음 날 쌓인 항목을 함께 본다 (REQ7)")
    void dailyLimitStopsUntilTomorrow() throws InterruptedException {
        watches.update(binding.id(), state -> new EventWatchState(state.timeZone(), state.checkpoint(), state.seen(),
            null, null, 0, 0, clock.instant().atZone(SEOUL).toLocalDate(), 12, state.enabledAt()));
        listing("m1");
        clearInvocations(feed);

        poller.checkAll();
        awaitIdle();

        verify(feed, never()).listEvents(anyString(), anyString(), any());
        assertThat(state().lastResult()).isEqualTo(EventWatchResult.DAILY_LIMIT);
        assertThat(state().checkpoint()).isEqualTo(BASE);
        assertThat(count("SELECT COUNT(*) FROM proactive_check WHERE user_id = ?")).isZero();

        clock.set(BASE.plus(Duration.ofDays(1)));
        wakeOnce();
        verify(feed).listEvents(eq(CONNECTOR), anyString(), eq(BASE));
    }


    @Test
    @DisplayName("연결 문제의 실패만 연속 실패로 세고 지점을 두며, 성공하면 풀린다 (REQ11)")
    void connectionFailuresAreCountedAndCleared() throws InterruptedException {
        when(feed.listEvents(anyString(), anyString(), any()))
            .thenReturn(ConnectorEventListing.failure(ConnectorCallError.CREDENTIAL_REJECTED))
            .thenReturn(ConnectorEventListing.failure(ConnectorCallError.UNAVAILABLE))
            .thenReturn(ConnectorEventListing.success(List.of(), false));

        poller.checkAll();
        assertThat(state().failures()).isEqualTo(1);
        poller.checkAll();
        assertThat(state().failures()).as("동시 호출 한도나 장애는 다시 연결할 일이 아니다").isEqualTo(1);
        assertThat(state().lastResult()).isEqualTo(EventWatchResult.FAILED);
        assertThat(state().checkpoint()).isEqualTo(BASE);
        poller.checkAll();
        awaitIdle();
        assertThat(state().failures()).isZero();
        assertThat(state().lastResult()).isEqualTo(EventWatchResult.NO_NEW);
    }


    @Test
    @DisplayName("격리 실행 공간 설정이 꺼진 동안 셸 toolset 이 켜진 에이전트는 깨우지 않고 지점을 둔다 (REQ9)")
    void shellToolsetBlocksWithoutIsolation() throws InterruptedException {
        when(toolsets.readEnabled(anyString(), anyString())).thenReturn(List.of("web", "terminal"));
        listing("m1");

        poller.checkAll();
        awaitIdle();

        assertThat(state().lastResult()).isEqualTo(EventWatchResult.BLOCKED);
        assertThat(state().checkpoint()).isEqualTo(BASE);
        assertThat(count("SELECT COUNT(*) FROM proactive_check WHERE user_id = ?")).isZero();
    }


    @Test
    @DisplayName("쓰기 허용 에이전트도 사건 실행은 읽기 경계의 toolset 목록으로 판정한다")
    void writesAllowedAgentStillNeedsReadOnlyToolsets() throws InterruptedException {
        agent.changeProactiveCheckWritesAllowed(true);
        agents.save(agent);
        when(toolsets.readEnabled(anyString(), anyString())).thenReturn(List.of("web", "browser"));
        listing("m1");

        poller.checkAll();
        awaitIdle();

        assertThat(state().lastResult()).isEqualTo(EventWatchResult.BLOCKED);
    }


    @Test
    @DisplayName("같은 연결에서는 바인딩 하나만 켜고, 조회 도구가 승인 없이 불리지 않으면 켜지 않는다 (REQ10)")
    void enableRequiresSingleBindingAndApprovalFreeTool() {
        Agent other = newAgent(user);
        bind(other);
        assertThatThrownBy(() -> watches.enable(user.id(), other.id(), CONNECTOR, SEOUL, clock.instant()))
            .isInstanceOfSatisfying(ApiException.class,
                ex -> assertThat(ex.code()).isEqualTo(ErrorCode.PROACTIVE_CHECK_UNAVAILABLE));

        watches.disable(user.id(), agent.id(), CONNECTOR);
        when(connector.readCatalog()).thenReturn(List.of(manifest("required")));
        ConnectorCatalogTimes.expire();
        assertThatThrownBy(() -> watches.enable(user.id(), other.id(), CONNECTOR, SEOUL, clock.instant()))
            .isInstanceOf(ApiException.class);
    }


    @Test
    @DisplayName("사건 실행이 남긴 읽지 않은 보고는 매일 깨우기를 건너뛰게 하지 않는다")
    void unreadEventReportDoesNotSkipDailyWake() throws InterruptedException {
        listing("m1");
        stub().willAnswer(command -> answer(eventFinding("m1")));
        ProactiveCheck event = wakeOnce();
        assertThat(event.report()).as("알린 항목이 있어 보고를 남긴다").isNotNull();

        Long scheduled = wake(user, agent, nothingNewOutput());

        assertThat(checks.findById(scheduled).orElseThrow().skippedReason()).isNotEqualTo(CheckSkippedReason.UNREAD_REPORT);
    }


    /** 확인 한 번으로 사건 실행 하나를 열고 끝나기를 기다린다. */
    private ProactiveCheck wakeOnce() throws InterruptedException {
        clock.set(clock.instant().plus(Duration.ofMinutes(15)));
        poller.checkAll();
        awaitIdle();
        return checks.findFirstByUserIdAndAgentIdOrderByIdDesc(user.id(), agent.id()).orElseThrow();
    }


    private String eventFinding(String id) {
        // 대역 Hermes 가 답할 때 부르므로 지금 시각이 살펴보기를 시작한 시각이다.
        String now = clock.instant().toString();
        return "<fos-check-result>\n{\"version\":3,\"outcome\":\"FINDINGS\",\"findings\":[{\"area\":\"mail\","
            + "\"topicKey\":\"" + id + "\",\"title\":\"답장이 필요한 메모\",\"sourceUrl\":\"https://notes.example.test/n/" + id
            + "\",\"checkedAt\":\"" + now + "\",\"freshness\":\"CURRENT\",\"whyItMatters\":\"마감이 걸렸다\","
            + "\"facts\":[\"합성 사실\"],\"next\":{\"type\":\"ACTION\",\"text\":\"답장한다\"}}]}\n</fos-check-result>";
    }


    private void listing(String... ids) {
        List<ConnectorEventListing.Item> items = Arrays.stream(ids)
            .map(id -> new ConnectorEventListing.Item(id, null, List.of("INBOX")))
            .toList();
        when(feed.listEvents(anyString(), anyString(), any())).thenReturn(ConnectorEventListing.success(items, false));
    }


    private ConnectorBinding bind(Agent target) {
        Instant now = clock.instant();
        ConnectorConnection connection = connections.findByUserIdAndConnectorId(user.id(), CONNECTOR).orElseGet(() -> {
            ConnectorConnection created = ConnectorConnection.pending(user.id(), CONNECTOR, now);
            created.ready(now);
            return connections.save(created);
        });
        ConnectorBinding created = ConnectorBinding.pending(target, connection, "notes", now);
        created.installed(false, now);
        created.ready(now);
        return bindings.save(created);
    }


    private EventWatchState state() {
        return EventWatchState.fromJson(jdbc.queryForObject(
            "SELECT event_watch_state FROM agent_connector_binding WHERE id = ?", String.class, binding.id()));
    }


    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class, user.id());
        return value == null ? 0 : value;
    }


    private static ConnectorManifest manifest(String approval) {
        return new ConnectorManifest(CONNECTOR, "메모", "", List.of(), LIST_TOOL, "notes", 2,
            List.of(new ConnectorTool(LIST_TOOL, "READ", approval, null, null)), List.of(), ConnectorAppearance.NONE,
            false, false, null, null, null, null, ConnectorUnknownErrors.NONE, null,
            new ConnectorEventWatch(LIST_TOOL, "메모", LINK));
    }


    private static HermesRunResult answer(String output) {
        return HermesRunResult.of(
            "run-" + UUID.randomUUID(), null, "completed", output, "model", "provider", TokenUsage.empty());
    }


    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }


    private StubHermesRunsClient stub() {
        return (StubHermesRunsClient)hermes;
    }
}
