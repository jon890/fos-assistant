package com.bifos.assistant.proactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.connector.connection.application.ConnectorEventWatches;
import com.bifos.assistant.connector.connection.domain.ConnectorBinding;
import com.bifos.assistant.connector.connection.domain.ConnectorConnection;
import com.bifos.assistant.connector.connection.domain.EventWatchState;
import com.bifos.assistant.connector.connection.infra.ConnectorBindingRepository;
import com.bifos.assistant.connector.connection.infra.ConnectorConnectionRepository;
import com.bifos.assistant.hermes.StubHermesRunsClient;
import com.bifos.assistant.hermes.application.dto.ConnectorAppearance;
import com.bifos.assistant.hermes.application.dto.ConnectorEventListing;
import com.bifos.assistant.hermes.application.dto.ConnectorEventWatch;
import com.bifos.assistant.hermes.application.dto.ConnectorManifest;
import com.bifos.assistant.hermes.application.dto.ConnectorTool;
import com.bifos.assistant.hermes.application.dto.ConnectorUnknownErrors;
import com.bifos.assistant.hermes.application.dto.HermesRunResult;
import com.bifos.assistant.hermes.application.port.HermesConnectorClient;
import com.bifos.assistant.hermes.application.port.HermesEventFeedClient;
import com.bifos.assistant.hermes.domain.TokenUsage;
import com.bifos.assistant.proactive.check.domain.ProactiveCheck;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.task.application.EventWatchPoller;
import com.bifos.assistant.testsupport.ConnectorCatalogTimes;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 사건 감지 통합 검사가 함께 쓰는 준비와 정리다(ADR-20261105 / event-watch). 사용자 하나와 에이전트 하나에 대역 플러그인 연결을 붙이고
 * 사건 감지를 켠다.
 *
 * <p>대시보드의 목록 조회와 카탈로그는 대역이고, 사건 실행은 대역 Hermes 가 답한다. 모든 값은 합성이다.
 */
abstract class EventWatchTestSupport extends ProactiveTestSupport {

    static final String CONNECTOR = "event-notes";
    static final String FINDING_TITLE = "답장이 필요한 메모";
    static final String LIST_TOOL = "list_notes";
    static final String LINK = "https://notes.example.test/n/{id}";
    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

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

    CurrentUser user;
    Agent agent;
    ConnectorBinding binding;


    @BeforeEach
    void prepareWatch() {
        reset(feed);
        // 분야 지침 스킬은 준비하지 않는다. 사건 실행과 살펴보기는 스킬 없이 돈다(REQ13).
        // 판정 plugin 이 깔린 설치 상태다. 깨우기와 켜기가 이 값을 다시 읽는다(REQ9).
        when(connector.readConnector(anyString(), anyString())).thenReturn(installed(true));
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
        jdbc.update("DELETE FROM notification WHERE user_id = ?", user.id());
        jdbc.update("DELETE FROM agent_connector_binding WHERE connection_id IN"
            + " (SELECT id FROM connector_connection WHERE user_id = ?)", user.id());
        jdbc.update("DELETE FROM connector_connection WHERE user_id = ?", user.id());
    }


    /** 그 profile 의 설치 상태다. {@code toolGuard} 가 거짓이면 옛 fos-ctx 나 옛 대시보드다. */
    static HermesConnectorClient.ConnectorState installed(boolean toolGuard) {
        return new HermesConnectorClient.ConnectorState("p", true, true, false, true, HermesConnectorClient.MODE_BIND, null, toolGuard);
    }


    /** 확인 한 번으로 사건 실행 하나를 열고 끝나기를 기다린다. */
    ProactiveCheck wakeOnce() throws InterruptedException {
        clock.set(clock.instant().plus(Duration.ofMinutes(15)));
        poller.checkAll();
        awaitIdle();
        return checks.findFirstByUserIdAndAgentIdOrderByIdDesc(user.id(), agent.id()).orElseThrow();
    }


    /** 항목마다 발견 한 칸을 쓴 버전 3 결과 블록이다. 대역 Hermes 가 답할 때 부르므로 지금 시각이 살펴보기를 시작한 시각이다. */
    String eventFinding(String... ids) {
        String now = clock.instant().toString();
        String findings = Arrays.stream(ids)
            .map(id -> "{\"area\":\"mail\",\"topicKey\":\"" + id + "\",\"title\":\"" + FINDING_TITLE + "\","
                + "\"sourceUrl\":\"https://notes.example.test/n/" + id + "\",\"checkedAt\":\"" + now + "\","
                + "\"freshness\":\"CURRENT\",\"whyItMatters\":\"마감이 걸렸다\",\"facts\":[\"합성 사실\"],"
                + "\"next\":{\"type\":\"ACTION\",\"text\":\"답장한다\"}}")
            .collect(Collectors.joining(","));
        return "<fos-check-result>\n{\"version\":3,\"outcome\":\"FINDINGS\",\"findings\":[" + findings + "]}\n</fos-check-result>";
    }


    void listing(String... ids) {
        List<ConnectorEventListing.Item> items = Arrays.stream(ids)
            .map(id -> new ConnectorEventListing.Item(id, null, List.of("INBOX")))
            .toList();
        when(feed.listEvents(anyString(), anyString(), any())).thenReturn(ConnectorEventListing.success(items, false));
    }


    ConnectorBinding bind(Agent target) {
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


    EventWatchState state() {
        return EventWatchState.fromJson(jdbc.queryForObject(
            "SELECT event_watch_state FROM agent_connector_binding WHERE id = ?", String.class, binding.id()));
    }


    long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class, user.id());
        return value == null ? 0 : value;
    }


    /** 확인 도구는 따로 두고 조회 도구에만 그 승인 방식을 준 선언이다. 확인 도구에 승인이 걸리면 카탈로그가 선언을 받지 않는다. */
    static ConnectorManifest watchToolApproval(String approval) {
        return new ConnectorManifest(CONNECTOR, "메모", "", List.of(), "verify_notes", "notes", 2,
            List.of(new ConnectorTool("verify_notes", "READ", "none", null, null), new ConnectorTool(LIST_TOOL, "READ", approval, null, null)),
            List.of(), ConnectorAppearance.NONE, false, false, null, null, null, null, ConnectorUnknownErrors.NONE, null,
            new ConnectorEventWatch(LIST_TOOL, "메모", LINK));
    }


    static ConnectorManifest manifest(String approval) {
        return new ConnectorManifest(CONNECTOR, "메모", "", List.of(), LIST_TOOL, "notes", 2,
            List.of(new ConnectorTool(LIST_TOOL, "READ", approval, null, null)), List.of(), ConnectorAppearance.NONE,
            false, false, null, null, null, null, ConnectorUnknownErrors.NONE, null,
            new ConnectorEventWatch(LIST_TOOL, "메모", LINK));
    }


    static HermesRunResult answer(String output) {
        return HermesRunResult.of(
            "run-" + UUID.randomUUID(), null, "completed", output, "model", "provider", TokenUsage.empty());
    }


    static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }


    StubHermesRunsClient stub() {
        return (StubHermesRunsClient)hermes;
    }
}
