package com.bifos.assistant.proactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.bifos.assistant.chat.conversation.domain.ChatMessage;
import com.bifos.assistant.chat.conversation.domain.type.MessageRole;
import com.bifos.assistant.chat.conversation.infra.ChatMessageRepository;
import com.bifos.assistant.connector.connection.domain.EventWatchState;
import com.bifos.assistant.hermes.application.dto.ConnectorCallError;
import com.bifos.assistant.hermes.application.dto.ConnectorEventListing;
import com.bifos.assistant.proactive.check.domain.ProactiveCheck;
import com.bifos.assistant.task.application.EventWatchService;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.LongProactiveCheckTimeouts;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 사건 감지의 알림 계약이다(ADR-20261105 / event-watch). 요구사항은 {@code docs/features/proactive/spec.md} 의 REQ5, REQ6, REQ11 이다.
 *
 * <p>시계는 고정이다. 서울 시간대로 켠 바인딩에서 {@code 2026-11-02T00:00Z} 는 09시, {@code 14:00Z} 는 23시, {@code 22:05Z} 는 다음 날
 * 07시 5분이다. 모든 값은 합성이다.
 */
@BackendIntegrationTest
@LongProactiveCheckTimeouts
class EventWatchNoticesTest extends EventWatchTestSupport {

    @Autowired
    ChatMessageRepository messages;

    @Autowired
    EventWatchService service;

    /** 서울 23시 15분 전이다. 확인 한 번이 15분을 더해 23시에 깨운다. */
    private static final Instant SEOUL_NIGHT = Instant.parse("2026-11-02T13:45:00Z");
    /** 서울 다음 날 06시 50분이다. 아직 조용한 시간이다. */
    private static final Instant SEOUL_BEFORE_DAWN = Instant.parse("2026-11-02T21:50:00Z");
    /** 서울 다음 날 07시 5분이다. */
    private static final Instant SEOUL_MORNING = Instant.parse("2026-11-02T22:05:00Z");


    @Test
    @DisplayName("알릴 항목이 있는 사건 실행은 개수만 담은 알림 한 건을 점검 대화로 내고, 입력 밖 식별자는 참고로 내려 세지 않는다 (REQ5)")
    void reportsOneCountOnlyNoticeToCheckConversation() throws InterruptedException {
        listing("m1", "m2");
        stub().willAnswer(command -> answer(eventFinding("m1", "zz")));

        ProactiveCheck check = wakeOnce();

        List<Map<String, Object>> rows = notices();
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst())
            .containsEntry("KIND", "EVENT_CHECK_REPORTED")
            .containsEntry("TITLE", "새 메모 2건 중 1건 확인이 필요해요")
            .containsEntry("BODY", "메모")
            .containsEntry("TARGET_TYPE", "CONVERSATION");
        assertThat(rows.getFirst().values()).as("알림에 항목의 제목을 남기지 않는다")
            .noneMatch(value -> String.valueOf(value).contains(FINDING_TITLE));
        assertThat(count("SELECT COUNT(*) FROM notification n JOIN conversation c ON c.public_id = n.target_public_id"
            + " WHERE n.user_id = ? AND c.id = " + check.conversationId())).as("누르면 그 점검 대화로 간다").isEqualTo(1);
        assertThat(answerText(check)).as("입력 밖 식별자의 발견은 참고로 내려간다").contains("이번에 새로 온 항목이 아니에요");
    }


    @Test
    @DisplayName("알릴 것이 없거나 입력 밖 식별자만 알린 사건 실행은 알림을 내지 않는다 (REQ5)")
    void nothingToReportMeansNoNotice() throws InterruptedException {
        listing("m1");
        stub().willAnswer(command -> answer(nothingNewOutput()));
        wakeOnce();
        listing("m2");
        stub().willAnswer(command -> answer(eventFinding("zz")));
        wakeOnce();

        assertThat(notices()).isEmpty();
    }


    @Test
    @DisplayName("조용한 시간에 끝난 실행은 알림을 미루고, 07시 뒤 첫 확인에서 밤사이 알림 한 건으로 낸다 (REQ6)")
    void quietHoursDeferUntilMorning() throws InterruptedException {
        clock.set(SEOUL_NIGHT);
        listing("m1", "m2");
        stub().willAnswer(command -> answer(eventFinding("m1")));
        ProactiveCheck night = wakeOnce();

        assertThat(notices()).isEmpty();
        assertThat(state().deferred()).isEqualTo(new EventWatchState.Deferred(1, 2, 1, night.conversationId(), "메모", "메모"));

        listing();
        clock.set(SEOUL_BEFORE_DAWN);
        poller.checkAll();
        assertThat(notices()).as("06시 50분은 아직 조용한 시간이다").isEmpty();

        clock.set(SEOUL_MORNING);
        poller.checkAll();
        awaitIdle();
        List<Map<String, Object>> rows = notices();
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst())
            .containsEntry("KIND", "EVENT_CHECK_REPORTED")
            .containsEntry("TITLE", "밤사이 새 메모 2건 중 1건 확인이 필요해요")
            .containsEntry("TARGET_TYPE", "CONVERSATION");
        assertThat(state().deferred()).isNull();

        poller.checkAll();
        assertThat(notices()).as("묶음은 한 번만 낸다").hasSize(1);
    }


    @Test
    @DisplayName("조용한 시간은 켤 때 저장한 시간대로 가린다. UTC 로 켠 바인딩은 서울 23시에 곧바로 알린다 (REQ6)")
    void quietHoursFollowStoredZone() throws InterruptedException {
        watches.enable(user.id(), agent.id(), CONNECTOR, ZoneOffset.UTC, clock.instant());
        clock.set(SEOUL_NIGHT);
        listing("m1");
        stub().willAnswer(command -> answer(eventFinding("m1")));

        wakeOnce();

        assertThat(notices()).extracting(row -> row.get("TITLE")).containsExactly("새 메모 1건 중 1건 확인이 필요해요");
        assertThat(state().deferred()).isNull();
    }


    @Test
    @DisplayName("연결 문제의 연속 실패가 네 번째에 닿을 때만 다시 연결 알림 한 건을 외부 서비스 연결로 내고, 풀린 뒤 다시 닿으면 한 건 더 낸다 (REQ11)")
    void reconnectNoticeOncePerFailureStreak() {
        failing();
        for (int i = 0; i < 3; i++) {
            poller.checkAll();
        }
        assertThat(notices()).isEmpty();

        poller.checkAll();
        List<Map<String, Object>> rows = notices();
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst())
            .containsEntry("KIND", "EVENT_WATCH_FAILED")
            .containsEntry("TITLE", "메모 다시 연결이 필요해요")
            .containsEntry("TARGET_TYPE", "CONNECTIONS")
            .containsEntry("TARGET_PUBLIC_ID", null);
        assertThat(state().checkpoint()).as("실패는 지점을 옮기지 않는다").isEqualTo(BASE);

        poller.checkAll();
        assertThat(notices()).as("이어지는 실패는 다시 알리지 않는다").hasSize(1);

        listing();
        poller.checkAll();
        assertThat(state().failures()).isZero();
        assertThat(state().failureNotified()).isFalse();

        failing();
        for (int i = 0; i < 4; i++) {
            poller.checkAll();
        }
        assertThat(notices()).hasSize(2);
    }


    @Test
    @DisplayName("조용한 시간에 실패가 네 번에 닿으면 알리지 않고, 07시 뒤에도 실패가 이어지면 그때 한 건을 낸다 (REQ6, REQ11)")
    void reconnectNoticeWaitsForMorning() {
        clock.set(SEOUL_NIGHT);
        failing();
        for (int i = 0; i < 5; i++) {
            poller.checkAll();
        }
        assertThat(state().failures()).isEqualTo(5);
        assertThat(notices()).isEmpty();

        clock.set(SEOUL_MORNING);
        poller.checkAll();

        assertThat(notices()).extracting(row -> row.get("KIND")).containsExactly("EVENT_WATCH_FAILED");
    }


    @Test
    @DisplayName("알릴 것이 없던 밤의 실행은 아침에 알림 없이 비우고, 실행 중에 사건 감지를 끄면 시간대를 몰라 바로 알린다 (REQ6)")
    void emptyMorningBatchAndDisabledWhileRunning() throws InterruptedException {
        clock.set(SEOUL_NIGHT);
        listing("m1");
        stub().willAnswer(command -> answer(nothingNewOutput()));
        wakeOnce();
        assertThat(state().deferred()).isEqualTo(new EventWatchState.Deferred(1, 1, 0, state().deferred().conversationId(),
            "메모", "메모"));

        listing();
        clock.set(SEOUL_MORNING);
        poller.checkAll();
        assertThat(state().deferred()).isNull();
        assertThat(notices()).as("알릴 것이 없던 밤은 아침 알림도 없다").isEmpty();

        clock.set(SEOUL_NIGHT.plus(Duration.ofDays(1)));
        listing("m2");
        stub().willAnswer(command -> {
            watches.disable(user.id(), agent.id(), CONNECTOR);
            return answer(eventFinding("m2"));
        });
        wakeOnce();

        assertThat(notices()).extracting(row -> row.get("TITLE")).containsExactly("새 메모 1건 중 1건 확인이 필요해요");
    }


    @Test
    @DisplayName("미룬 알림이 있는 채로 07시 전에 끄면 지우기 전에 밤사이 알림 한 건을 낸다 (REQ6)")
    void disablingFlushesDeferredNotice() throws InterruptedException {
        clock.set(SEOUL_NIGHT);
        listing("m1");
        stub().willAnswer(command -> answer(eventFinding("m1")));
        wakeOnce();
        assertThat(notices()).isEmpty();

        clock.set(SEOUL_BEFORE_DAWN);
        service.update(user, agent.code(), CONNECTOR, false, "");

        assertThat(notices()).extracting(row -> row.get("TITLE")).containsExactly("밤사이 새 메모 1건 중 1건 확인이 필요해요");
    }


    private void failing() {
        when(feed.listEvents(anyString(), anyString(), any()))
            .thenReturn(ConnectorEventListing.failure(ConnectorCallError.CREDENTIAL_REJECTED));
    }


    private List<Map<String, Object>> notices() {
        return jdbc.queryForList(
            "SELECT kind, title, body, target_type, target_public_id FROM notification WHERE user_id = ? ORDER BY id",
            user.id());
    }


    private String answerText(ProactiveCheck check) {
        return messages.findByConversationIdOrderByIdAsc(check.conversationId()).stream()
            .filter(message -> message.role() == MessageRole.ASSISTANT)
            .map(ChatMessage::content)
            .collect(Collectors.joining("\n"));
    }
}
