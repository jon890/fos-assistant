package com.bifos.assistant.attention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bifos.assistant.attention.application.AttentionEventCleaner;
import com.bifos.assistant.attention.application.AttentionMetricsService;
import com.bifos.assistant.attention.application.dto.AttentionMetric;
import com.bifos.assistant.attention.domain.type.AttentionEventType;
import com.bifos.assistant.attention.domain.type.AttentionTrigger;
import com.bifos.assistant.feedback.application.FeedbackLabeler;
import com.bifos.assistant.feedback.application.ReactionLedger;
import com.bifos.assistant.feedback.application.dto.ControlEntry;
import com.bifos.assistant.feedback.application.dto.FeedbackEntry;
import com.bifos.assistant.feedback.domain.type.FeedbackActor;
import com.bifos.assistant.feedback.domain.type.FeedbackEventType;
import com.bifos.assistant.feedback.domain.type.FeedbackSubjectType;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.UUID;
import com.bifos.assistant.admin.attention.presentation.AttentionAdminController;
import com.bifos.assistant.admin.attention.presentation.AttentionAdminDtos.MetricsResponse;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.TestClock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 지표 사건을 {@code trigger} 별로 세는 것과 보관 기간 정리를 본다. 셈은 {@code docs/features/attention.md} 의 「지표(먼저 알리기와 지금 화면의 판정)」 다.
 *
 * <p>지표는 모든 사용자의 사건을 세므로 검사마다 반응 기록의 지금 화면 사건을 비운다. 사건은 반응 기록으로 바로 넣는다. 시각은 이 검사의 시계가 정한다.
 */
@BackendIntegrationTest
class AttentionMetricsServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-04T09:00:00Z");
    private Long user;

    @Autowired
    TestClock clock;

    @Autowired
    AttentionMetricsService metrics;

    @Autowired
    AttentionEventCleaner cleaner;

    @Autowired
    ReactionLedger ledger;

    @Autowired
    AppUserRepository users;

    @Autowired
    JdbcTemplate jdbc;


    @BeforeEach
    void setUp() {
        clock.set(NOW);
        clearEvents();
        String email = "attention-metrics-" + UUID.randomUUID() + "@example.com";
        user = users.save(AppUser.of(email, email, 1L, UserRole.MEMBER)).id();
    }


    @AfterEach
    void tearDown() {
        clearEvents();
        jdbc.update("DELETE FROM app_user WHERE id = ?", user);
    }


    private void clearEvents() {
        jdbc.update("DELETE FROM decision_feedback_event WHERE subject_type = 'ATTENTION_ITEM' OR card_key IS NOT NULL");
    }


    @Test
    @DisplayName("NOW 로 보인 실패 둘 가운데 하나는 10초 뒤 열고 하나는 행동 없이 숨기면 그대로 센다")
    void countsShownActedAndHiddenPerTrigger() {
        Instant shownAt = NOW.minus(Duration.ofHours(2));
        save("conversation:a", AttentionEventType.SHOWN, shownAt);
        save("conversation:a", AttentionEventType.OPENED, shownAt.plusSeconds(10));
        save("conversation:b", AttentionEventType.SHOWN, shownAt);
        save("conversation:b", AttentionEventType.HIDDEN, shownAt.plusSeconds(30));

        AttentionMetric row = onlyRow(metrics.metrics(30));

        assertThat(row.trigger()).isEqualTo(AttentionTrigger.EXECUTION_FAILED);
        assertThat(row.shown()).as("shown").isEqualTo(2);
        assertThat(row.acted()).as("acted").isEqualTo(1);
        assertThat(row.hidden()).as("hidden").isEqualTo(1);
        assertThat(row.snoozed()).as("snoozed").isZero();
        assertThat(row.nowShown()).as("nowShown").isEqualTo(2);
        assertThat(row.nowHiddenWithoutAction()).as("nowHiddenWithoutAction").isEqualTo(1);
        assertThat(row.staleShown()).as("staleShown").isZero();
        assertThat(row.medianSecondsToFirstAction())
            .as("medianSecondsToFirstAction")
            .isEqualTo(10L);
    }


    @Test
    @DisplayName("91일 전 사건은 30일 지표에 들지 않고 정리가 지운 뒤 지운 수 1 을 돌려준다")
    void excludesAndCleansEventsOlderThanRetention() {
        save("conversation:old", AttentionEventType.SHOWN, NOW.minus(Duration.ofDays(91)));
        save("conversation:new", AttentionEventType.SHOWN, NOW.minus(Duration.ofDays(1)));

        AttentionMetric row = onlyRow(metrics.metrics(30));
        int deleted = cleaner.clean(NOW);

        assertThat(row.shown()).as("30일 안에 보인 항목 수").isEqualTo(1);
        assertThat(deleted).isEqualTo(1);
        assertThat(jdbc.queryForList(
            "SELECT subject_key FROM decision_feedback_event WHERE user_id = ?", String.class, user))
                .containsExactly("conversation:new");
    }


    @ParameterizedTest
    @ValueSource(ints = {0, 91})
    @DisplayName("기간이 1 부터 90 밖이면 VALIDATION_FAILED 다")
    void rejectsDaysOutsideRange(int days) {
        assertThatThrownBy(() -> metrics.metrics(days))
            .isInstanceOf(ApiException.class)
            .extracting(failure -> ((ApiException)failure).code())
            .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }


    @Test
    @DisplayName("MEMBER 역할이 지표를 부르면 FORBIDDEN 이고 ADMIN 역할은 받는다")
    void memberMetricsIsForbiddenAndAdminGetsRows() {
        save("conversation:a", AttentionEventType.SHOWN, NOW.minus(Duration.ofHours(1)));
        CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
        doCallRealMethod().when(currentUser).requireAdmin();
        AttentionAdminController controller = new AttentionAdminController(metrics, currentUser);

        when(currentUser.require()).thenReturn(new CurrentUser(user, "member@example.com", "아빠", 1L, UserRole.MEMBER));
        assertThatThrownBy(() -> controller.metrics(30))
            .isInstanceOf(ApiException.class)
            .extracting(failure -> ((ApiException)failure).code())
            .isEqualTo(ErrorCode.FORBIDDEN);

        when(currentUser.require()).thenReturn(new CurrentUser(user, "admin@example.com", "엄마", 1L, UserRole.ADMIN));
        MetricsResponse response = controller.metrics(30);
        assertThat(response.days()).isEqualTo(30);
        assertThat(response.rows()).singleElement().satisfies(row -> {
            assertThat(row.trigger()).isEqualTo("EXECUTION_FAILED");
            assertThat(row.shown()).isEqualTo(1);
        });
    }


    @Test
    @DisplayName("판정을 옮기지 않은 이관 사건은 센 항목이지만 NOW 로 보인 항목에는 들지 않는다")
    void countsLegacyShownWithoutLevelButNotAsNow() {
        saveLegacyShown("conversation:legacy", NOW.minus(Duration.ofHours(3)));

        AttentionMetric row = onlyRow(metrics.metrics(30));

        assertThat(row.shown()).isEqualTo(1);
        assertThat(row.nowShown()).isZero();
    }


    /**
     * 실패 trigger 의 {@code NOW} 사건 한 줄을 반응 기록에 넣는다. 상태는 항목마다 하나로 둔다. 숨김은 카드가 있는 제어 사건으로 넣는다.
     */
    private void save(String itemKey, AttentionEventType type, Instant at) {
        String state = "state-" + itemKey;
        if (type == AttentionEventType.HIDDEN) {
            FeedbackEntry hide = FeedbackEntry.ofKey(
                user, FeedbackSubjectType.ATTENTION_ITEM, itemKey, FeedbackEventType.DISMISSED, FeedbackActor.USER, at)
                .version(state)
                .reason(FeedbackLabeler.ATTENTION_HIDE);
            ledger.append(new ControlEntry(hide, "FAILURES", null, null));
            return;
        }
        FeedbackEntry view = FeedbackEntry.ofKey(
            user,
            FeedbackSubjectType.ATTENTION_ITEM,
            itemKey,
            FeedbackEventType.valueOf(type.name()),
            type == AttentionEventType.SHOWN ? FeedbackActor.SYSTEM : FeedbackActor.USER,
            at)
            .version(state)
            .reason(AttentionTrigger.EXECUTION_FAILED + "@NOW");
        ledger.append(new ControlEntry(view, null, null, null));
    }


    /** 옛 {@code attention_event} 에서 이관한 노출처럼 판정이 없는 사건 한 줄을 넣는다. */
    private void saveLegacyShown(String itemKey, Instant at) {
        FeedbackEntry view = FeedbackEntry.ofKey(
            user, FeedbackSubjectType.ATTENTION_ITEM, itemKey, FeedbackEventType.SHOWN, FeedbackActor.SYSTEM, at)
            .version("state-" + itemKey)
            .reason(AttentionTrigger.EXECUTION_FAILED.name());
        ledger.append(new ControlEntry(view, null, null, "legacy:attention_event:" + itemKey));
    }


    private static AttentionMetric onlyRow(List<AttentionMetric> rows) {
        assertThat(rows).as("지표 줄").hasSize(1);
        return rows.getFirst();
    }
}
