package com.bifos.assistant.proactive;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.chat.conversation.application.ConversationWriter;
import com.bifos.assistant.chat.purge.application.ConversationPurger;
import com.bifos.assistant.followup.application.FollowUpService;
import com.bifos.assistant.followup.application.dto.FollowUpSnapshot;
import com.bifos.assistant.followup.application.dto.SuggestedItem;
import com.bifos.assistant.followup.application.dto.SuggestionOutcome;
import com.bifos.assistant.followup.domain.FollowUp;
import com.bifos.assistant.followup.domain.type.FollowUpStatus;
import com.bifos.assistant.followup.infra.FollowUpRepository;
import com.bifos.assistant.followup.infra.FollowUpSuggestionBodies;
import com.bifos.assistant.proactive.check.domain.ProactiveCheck;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.OverrideProperties;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 제안 저장 경로(기능 스위치 켬)를 실제 DB 로 본다(ADR-20261101 / proactive-suggestion). 제안은 새 테이블이 아니라 할 일의
 * {@code PROPOSED} 줄이다. 스위치가 꺼졌을 때는 {@link ProactiveSuggestionDisabledTest} 가 본다. 모든 값은 합성이다.
 */
@BackendIntegrationTest
@OverrideProperties("assistant.proactive-suggestion.enabled=true")
class ProactiveSuggestionStoreTest extends ProactiveLoopTestSupport {

    private static final String DETAIL = "{\"why\":\"합성 근거\",\"goalRef\":\"goal-1\"}";

    @Autowired
    FollowUpService followUps;

    @Autowired
    FollowUpRepository followUpRows;

    @Autowired
    FollowUpSuggestionBodies bodies;

    @Autowired
    ConversationWriter conversationWriter;

    @Autowired
    ConversationPurger purger;

    private final List<Long> owners = new ArrayList<>();


    @AfterEach
    void removeFollowUps() {
        for (Long userId : owners) {
            jdbc.update("DELETE FROM follow_up WHERE user_id = ?", userId);
        }
        owners.clear();
    }


    @Test
    @DisplayName("스위치를 켜고 깨우면 결과 블록의 제안이 그 사용자와 점검 대화의 PROPOSED 줄로 저장되고 본문을 열 수 있다")
    void storesSuggestionsAsProposedRows() throws InterruptedException {
        CurrentUser user = owner();
        Agent agent = newAgent(user);

        Long checkId = wake(user, agent, suggestionOutput());

        ProactiveCheck check = checks.findById(checkId).orElseThrow();
        List<FollowUp> rows = rowsOf(user);
        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.status()).isEqualTo(FollowUpStatus.PROPOSED);
            assertThat(row.userId()).isEqualTo(user.id());
            assertThat(row.conversationId()).isEqualTo(check.conversationId());
            assertThat(row.proposedByExecutionId()).isNotNull();
            assertThat(row.dedupeKey()).hasSize(64);
            assertThat(row.expiresAt()).isAfter(clock.instant());
        });
        FollowUp withDetail = rows.stream().filter(row -> row.detailJson() != null).findFirst().orElseThrow();
        assertThat(bodies.open(user.id(), withDetail.dedupeKey(), withDetail.detailJson(), withDetail.detailKeyId()))
            .hasValueSatisfying(json -> assertThat(json).contains("합성 근거"));
        assertThat(rows.stream().filter(row -> row.detailJson() == null)).hasSize(1);
    }


    @Test
    @DisplayName("같은 실행의 같은 결과를 두 번 넘기면 제안이 하나이고 둘째는 DUPLICATE 다")
    void sameResponseTwiceKeepsOneSuggestion() throws InterruptedException {
        CurrentUser user = owner();
        Agent agent = newAgent(user);
        wake(user, agent, suggestionOutput());
        Long executionId = rowsOf(user).getFirst().proposedByExecutionId();

        List<SuggestionOutcome> again = followUps.proposeSuggestion(
            executionId,
            List.of(
                new SuggestedItem("s1", "예시 제안 하나", DETAIL),
                new SuggestedItem("s2", "예시 제안 둘", null)));

        assertThat(again).containsExactly(SuggestionOutcome.DUPLICATE, SuggestionOutcome.DUPLICATE);
        assertThat(rowsOf(user)).hasSize(2);
    }


    @Test
    @DisplayName("제안은 실행 줄의 사용자에게만 생기고 다른 사용자의 줄과 섞이지 않는다")
    void suggestionBelongsToExecutionOwner() throws InterruptedException {
        CurrentUser first = owner();
        CurrentUser second = owner();
        wake(first, newAgent(first), suggestionOutput());
        wake(second, newAgent(second), singleSuggestionOutput("둘째 사용자의 제안"));

        assertThat(rowsOf(first)).hasSize(2);
        assertThat(rowsOf(second)).singleElement().satisfies(row -> assertThat(row.title()).isEqualTo("둘째 사용자의 제안"));
        Long foreign = jdbc.queryForObject(
            "SELECT COUNT(*) FROM follow_up f JOIN agent_execution e ON e.id = f.proposed_by_execution_id"
                + " WHERE f.user_id <> e.user_id AND f.user_id IN (?, ?)",
            Long.class,
            first.id(),
            second.id());
        assertThat(foreign).as("실행 주인과 다른 사용자의 제안 줄").isZero();
    }


    @Test
    @DisplayName("대화를 지우면 그 대화의 제안 줄과 본문이 지워지고 받아들인 할 일은 남기되 제안 본문만 비운다")
    void deletingConversationErasesSuggestions() throws InterruptedException {
        CurrentUser user = owner();
        Agent agent = newAgent(user);
        Long checkId = wake(user, agent, suggestionOutput());
        Long conversationId = checks.findById(checkId).orElseThrow().conversationId();
        FollowUp withDetail = rowsOf(user).stream().filter(row -> row.detailJson() != null).findFirst().orElseThrow();
        followUps.accept(user, withDetail.publicId());

        conversationWriter.deleteIfActive(conversationId, user.id(), clock.instant().minusSeconds(60));
        assertThat(purger.purgeDue(clock.instant())).isGreaterThanOrEqualTo(1);

        List<FollowUp> left = rowsOf(user);
        assertThat(left).singleElement().satisfies(row -> {
            assertThat(row.publicId()).isEqualTo(withDetail.publicId());
            assertThat(row.status()).isEqualTo(FollowUpStatus.OPEN);
            assertThat(row.detailJson()).isNull();
            assertThat(row.detailKeyId()).isNull();
        });
    }


    @Test
    @DisplayName("받아들이면 같은 줄이 OPEN 이 되고 할 일이 하나 더 생기지 않으며 만료 시각을 비운다")
    void acceptKeepsSameRow() throws InterruptedException {
        CurrentUser user = owner();
        Agent agent = newAgent(user);
        wake(user, agent, singleSuggestionOutput("받아들일 제안"));
        FollowUp proposed = rowsOf(user).getFirst();

        FollowUpSnapshot accepted = followUps.accept(user, proposed.publicId());

        assertThat(accepted.status()).isEqualTo(FollowUpStatus.OPEN);
        assertThat(rowsOf(user)).singleElement().satisfies(row -> {
            assertThat(row.id()).isEqualTo(proposed.id());
            assertThat(row.status()).isEqualTo(FollowUpStatus.OPEN);
            assertThat(row.expiresAt()).isNull();
        });
    }


    @Test
    @DisplayName("만료 시각이 지난 제안은 할 일 목록에서 빠지고 받아들인 할 일은 만료되지 않는다")
    void expiredSuggestionLeavesList() throws InterruptedException {
        CurrentUser user = owner();
        Agent agent = newAgent(user);
        wake(user, agent, suggestionOutput());
        FollowUp toAccept = rowsOf(user).getFirst();
        followUps.accept(user, toAccept.publicId());
        assertThat(followUps.list(user)).hasSize(2);

        clock.set(clock.instant().plus(Duration.ofDays(8)));

        assertThat(followUps.list(user)).extracting(FollowUpSnapshot::publicId).containsExactly(toAccept.publicId());
    }


    @Test
    @DisplayName("제목이 없거나 본문이 상한을 넘는 항목은 INVALID 로 건너뛰고 나머지는 저장한다")
    void invalidItemIsSkipped() throws InterruptedException {
        CurrentUser user = owner();
        Agent agent = newAgent(user);
        wake(user, agent, singleSuggestionOutput("기준 제안"));
        Long executionId = rowsOf(user).getFirst().proposedByExecutionId();
        String tooLong = "{\"x\":\"" + "가".repeat(FollowUpService.SUGGESTION_DETAIL_MAX) + "\"}";

        List<SuggestionOutcome> outcomes = followUps.proposeSuggestion(
            executionId,
            List.of(
                new SuggestedItem("a", "  ", null),
                new SuggestedItem("b", "너무 큰 본문", tooLong),
                new SuggestedItem("c", "정상 제안", DETAIL)));

        assertThat(outcomes)
            .containsExactly(SuggestionOutcome.INVALID, SuggestionOutcome.INVALID, SuggestionOutcome.CREATED);
        assertThat(rowsOf(user)).hasSize(2);
    }


    private CurrentUser owner() {
        CurrentUser user = newUser();
        owners.add(user.id());
        return user;
    }


    private List<FollowUp> rowsOf(CurrentUser user) {
        return followUpRows.findAll().stream()
            .filter(row -> row.userId().equals(user.id()))
            .toList();
    }


    /** 발견 하나와 제안 둘을 담은 결과 블록이다. 첫 제안만 본문을 갖는다. */
    private String suggestionOutput() {
        return candidateOutput()
            .replace(
                "\"report\":{",
                "\"suggestions\":[{\"id\":\"s1\",\"title\":\"예시 제안 하나\",\"detail\":" + DETAIL
                    + "},{\"id\":\"s2\",\"title\":\"예시 제안 둘\"}],\"report\":{");
    }


    private String singleSuggestionOutput(String title) {
        return candidateOutput()
            .replace(
                "\"report\":{",
                "\"suggestions\":[{\"id\":\"only\",\"title\":\"" + title + "\"}],\"report\":{");
    }
}
