package com.bifos.assistant.attention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.bifos.assistant.attention.application.AttentionControlService;
import com.bifos.assistant.attention.application.AttentionService;
import com.bifos.assistant.attention.application.dto.AttentionCard;
import com.bifos.assistant.attention.application.dto.AttentionItem;
import com.bifos.assistant.attention.application.dto.AttentionView;
import com.bifos.assistant.attention.domain.type.AttentionLevel;
import com.bifos.assistant.attention.domain.type.AttentionTrigger;
import com.bifos.assistant.attention.domain.type.CardKey;
import com.bifos.assistant.followup.application.FollowUpService;
import com.bifos.assistant.followup.domain.FollowUp;
import com.bifos.assistant.followup.domain.type.FollowUpStatus;
import com.bifos.assistant.followup.infra.FollowUpRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.TestClock;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 제안, 곧 할 일의 {@code PROPOSED} 줄이 「내 차례」 카드의 제안 카드로 어떻게 보이는지 실제 DB 로 본다(ADR-20261101 /
 * proactive-suggestion). 규칙은 {@code docs/features/attention.md} 의 「후보와 trigger」 의 {@code FOLLOW_UP_PROPOSED} 줄이다. 모든
 * 값은 합성이다.
 */
@BackendIntegrationTest
class SuggestionCandidatesTest {

    private static final Instant NOW = Instant.parse("2026-10-04T09:00:00Z");

    @Autowired
    TestClock clock;

    @Autowired
    AttentionService attention;

    @Autowired
    AttentionControlService controls;

    @Autowired
    FollowUpService followUpService;

    @Autowired
    FollowUpRepository followUps;

    @Autowired
    AppUserRepository users;

    @Autowired
    JdbcTemplate jdbc;

    private final List<Long> createdUsers = new ArrayList<>();
    private CurrentUser dad;


    @BeforeEach
    void setUp() {
        clock.set(NOW);
        dad = member();
    }


    @AfterEach
    void tearDown() {
        for (Long userId : createdUsers) {
            jdbc.update("DELETE FROM decision_feedback_event WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM attention_event WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM attention_control WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM follow_up WHERE user_id = ?", userId);
        }
        createdUsers.clear();
    }


    @Test
    @DisplayName("제안은 제안 카드로 LATER 이고 건수에 들지 않으며, 만료된 제안은 보이지 않는다")
    void showsLiveSuggestionsAndHidesExpired() {
        FollowUp live = suggestion("live", "합성 공고 마감 확인", NOW.plus(Duration.ofDays(7)));
        suggestion("expired", "지난 합성 제안", NOW.minusSeconds(1));

        AttentionView view = attention.view(dad);

        List<AttentionItem> items = card(view).items();
        assertThat(items)
            .extracting(AttentionItem::itemKey, AttentionItem::trigger, AttentionItem::level, AttentionItem::title)
            .containsExactly(tuple(
                "follow_up:" + live.publicId(),
                AttentionTrigger.FOLLOW_UP_PROPOSED,
                AttentionLevel.LATER,
                "합성 공고 마감 확인"));
        assertThat(items.getFirst().why().confidence().name()).isEqualTo("MODEL_INFERRED");
        assertThat(items.getFirst().followUp().proposed()).isTrue();
        assertThat(view.nowCount()).isZero();
    }


    @Test
    @DisplayName("숨긴 제안과 미룬 제안은 반응 기록에 사건이 남고 카드에서 빠진다")
    void hiddenAndSnoozedSuggestionsLeaveTheCard() {
        FollowUp hidden = suggestion("hide", "숨길 합성 제안", NOW.plus(Duration.ofDays(7)));
        FollowUp snoozed = suggestion("snooze", "미룰 합성 제안", NOW.plus(Duration.ofDays(7)));
        List<AttentionItem> items = card(attention.view(dad)).items();
        AttentionItem hideItem = itemOf(items, hidden);
        AttentionItem snoozeItem = itemOf(items, snoozed);

        controls.hide(dad, CardKey.NEEDS_ME, hideItem.itemKey(), hideItem.stateKey());
        controls.snooze(dad, CardKey.NEEDS_ME, snoozeItem.itemKey(), NOW.plus(Duration.ofHours(2)));

        assertThat(card(attention.view(dad)).items()).isEmpty();
        assertThat(jdbc.queryForList(
            "SELECT subject_key, event_type FROM decision_feedback_event"
                + " WHERE user_id = ? AND event_type IN ('DISMISSED', 'POSTPONED') ORDER BY id",
            dad.id()))
                .extracting(row -> row.get("SUBJECT_KEY"), row -> row.get("EVENT_TYPE"))
                .containsExactly(
                    tuple("follow_up:" + hidden.publicId(), "DISMISSED"),
                    tuple("follow_up:" + snoozed.publicId(), "POSTPONED"));

        clock.set(NOW.plus(Duration.ofHours(3)));
        assertThat(card(attention.view(dad)).items())
            .as("미루기가 끝나면 다시 보이고 숨긴 제안은 그대로 빠진다")
            .extracting(AttentionItem::itemKey)
            .containsExactly("follow_up:" + snoozed.publicId());
    }


    @Test
    @DisplayName("받아들인 제안은 같은 줄이 OPEN 이 될 뿐 할 일을 더 만들지 않는다")
    void acceptingTurnsTheSameRowOpen() {
        FollowUp proposed = suggestion("accept", "받아들일 합성 제안", NOW.plus(Duration.ofDays(7)));

        followUpService.accept(dad, proposed.publicId());

        assertThat(followUps.findAll().stream().filter(row -> row.userId().equals(dad.id())))
            .extracting(FollowUp::publicId, FollowUp::status)
            .containsExactly(tuple(proposed.publicId(), FollowUpStatus.OPEN));
        assertThat(card(attention.view(dad)).items())
            .extracting(AttentionItem::itemKey, AttentionItem::trigger)
            .containsExactly(tuple("follow_up:" + proposed.publicId(), AttentionTrigger.FOLLOW_UP_OPEN));
    }


    private FollowUp suggestion(String key, String title, Instant expiresAt) {
        return followUps.save(FollowUp.suggested(
            dad.id(),
            null,
            null,
            title,
            FollowUpService.titleKey(title),
            "test:" + key + ":" + UUID.randomUUID(),
            null,
            null,
            expiresAt,
            NOW.minus(Duration.ofHours(1))));
    }


    private CurrentUser member() {
        String email = "suggestion-candidates-" + UUID.randomUUID() + "@example.com";
        AppUser user = users.save(AppUser.of(email, email, 1L, UserRole.MEMBER));
        createdUsers.add(user.id());
        return new CurrentUser(user.id(), user.email(), user.displayName(), user.groupId(), user.role());
    }


    private static AttentionItem itemOf(List<AttentionItem> items, FollowUp row) {
        return items.stream()
            .filter(item -> item.itemKey().equals("follow_up:" + row.publicId()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("제안 항목이 없다: " + row.publicId()));
    }


    private static AttentionCard card(AttentionView view) {
        return view.cards().stream()
            .filter(card -> card.key() == CardKey.NEEDS_ME)
            .findFirst()
            .orElseThrow(() -> new AssertionError("나를 기다리는 카드가 응답에 없다: " + view.cards()));
    }
}
