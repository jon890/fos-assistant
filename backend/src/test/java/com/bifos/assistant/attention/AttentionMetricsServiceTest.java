package com.bifos.assistant.attention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bifos.assistant.attention.application.AttentionEventCleaner;
import com.bifos.assistant.attention.application.AttentionMetricsService;
import com.bifos.assistant.attention.application.model.AttentionMetric;
import com.bifos.assistant.attention.domain.AttentionEvent;
import com.bifos.assistant.attention.domain.type.AttentionEventType;
import com.bifos.assistant.attention.domain.type.AttentionLevel;
import com.bifos.assistant.attention.domain.type.AttentionTrigger;
import com.bifos.assistant.attention.infra.AttentionEventRepository;
import com.bifos.assistant.attention.presentation.AttentionAdminController;
import com.bifos.assistant.attention.presentation.AttentionDtos.MetricsResponse;
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
 * <p>지표는 모든 사용자의 사건을 세므로 검사마다 사건 표를 비운다. 사건은 저장소로 바로 넣는다. 시각은 이 검사의 시계가 정한다.
 */
@BackendIntegrationTest
class AttentionMetricsServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-04T09:00:00Z");
    private static final Long USER = 7_001L;

    @Autowired
    TestClock clock;

    @Autowired
    AttentionMetricsService metrics;

    @Autowired
    AttentionEventCleaner cleaner;

    @Autowired
    AttentionEventRepository events;

    @BeforeEach
    void setUp() {
        clock.set(NOW);
        events.deleteAll();
    }

    @AfterEach
    void tearDown() {
        events.deleteAll();
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
        assertThat(events.findAll()).extracting(AttentionEvent::itemKey).containsExactly("conversation:new");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 91})
    @DisplayName("기간이 1 부터 90 밖이면 VALIDATION_FAILED 다")
    void rejectsDaysOutsideRange(int days) {
        assertThatThrownBy(() -> metrics.metrics(days))
                .isInstanceOf(ApiException.class)
                .extracting(failure -> ((ApiException) failure).code())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("MEMBER 역할이 지표를 부르면 FORBIDDEN 이고 ADMIN 역할은 받는다")
    void memberMetricsIsForbiddenAndAdminGetsRows() {
        save("conversation:a", AttentionEventType.SHOWN, NOW.minus(Duration.ofHours(1)));
        CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
        doCallRealMethod().when(currentUser).requireAdmin();
        AttentionAdminController controller = new AttentionAdminController(metrics, currentUser);

        when(currentUser.require()).thenReturn(new CurrentUser(USER, "member@example.com", "아빠", 1L, UserRole.MEMBER));
        assertThatThrownBy(() -> controller.metrics(30))
                .isInstanceOf(ApiException.class)
                .extracting(failure -> ((ApiException) failure).code())
                .isEqualTo(ErrorCode.FORBIDDEN);

        when(currentUser.require()).thenReturn(new CurrentUser(USER, "admin@example.com", "엄마", 1L, UserRole.ADMIN));
        MetricsResponse response = controller.metrics(30);
        assertThat(response.days()).isEqualTo(30);
        assertThat(response.rows()).singleElement().satisfies(row -> {
            assertThat(row.trigger()).isEqualTo("EXECUTION_FAILED");
            assertThat(row.shown()).isEqualTo(1);
        });
    }

    /** 실패 trigger 의 {@code NOW} 사건 한 줄을 넣는다. 상태는 항목마다 하나로 둔다. */
    private void save(String itemKey, AttentionEventType type, Instant at) {
        events.save(AttentionEvent.of(
                USER,
                itemKey,
                "state-" + itemKey,
                AttentionTrigger.EXECUTION_FAILED,
                AttentionLevel.NOW,
                type,
                false,
                at));
    }

    private static AttentionMetric onlyRow(List<AttentionMetric> rows) {
        assertThat(rows).as("지표 줄").hasSize(1);
        return rows.getFirst();
    }
}
