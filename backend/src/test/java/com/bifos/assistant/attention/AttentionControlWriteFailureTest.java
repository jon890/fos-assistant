package com.bifos.assistant.attention;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bifos.assistant.attention.application.AttentionControlService;
import com.bifos.assistant.attention.application.AttentionEventWriter;
import com.bifos.assistant.attention.application.AttentionService;
import com.bifos.assistant.attention.application.SuggestionFeedback;
import com.bifos.assistant.attention.application.dto.AttentionCandidate;
import com.bifos.assistant.attention.application.dto.AttentionSnapshot;
import com.bifos.assistant.attention.config.AttentionProperties;
import com.bifos.assistant.attention.domain.type.AttentionTrigger;
import com.bifos.assistant.attention.domain.type.CardKey;
import com.bifos.assistant.feedback.application.ReactionLedger;
import com.bifos.assistant.feedback.application.dto.ControlEntry;
import com.bifos.assistant.feedback.application.dto.FeedbackEntry;
import com.bifos.assistant.feedback.application.dto.ReactionRecord;
import com.bifos.assistant.feedback.domain.type.FeedbackActor;
import com.bifos.assistant.feedback.domain.type.FeedbackEventType;
import com.bifos.assistant.feedback.domain.type.FeedbackSubjectType;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * 제어 사건을 남기지 못하면 숨기기, 미루기, 되돌리기 요청도 실패해야 한다. 현재 숨김과 미루기를 이 사건에서 구하므로, 사건 없이 성공한
 * 응답은 사용자가 숨긴 항목을 다시 보이게 한다. 반응 기록을 대역으로 바꿔 쓰기 실패를 만든다.
 */
class AttentionControlWriteFailureTest {

    private static final Instant NOW = Instant.parse("2026-10-04T09:00:00Z");
    private static final CurrentUser USER = new CurrentUser(1L, "dad@example.com", "아빠", 1L, UserRole.MEMBER);
    private static final String ITEM = "conversation:11111111-1111-1111-1111-111111111111";

    private ReactionLedger ledger;
    private AttentionControlService controls;


    @BeforeEach
    void setUp() {
        ledger = mock(ReactionLedger.class);
        when(ledger.latestControl(any(), any(), any())).thenReturn(Optional.empty());
        when(ledger.append(any())).thenThrow(new IllegalStateException("검사가 낸 쓰기 실패"));
        AttentionService attention = mock(AttentionService.class);
        AttentionCandidate candidate = candidate();
        when(attention.snapshot(USER))
            .thenReturn(new AttentionSnapshot(NOW, Map.of(CardKey.FAILURES, List.of(candidate)), Set.of(), List.of()));
        SuggestionFeedback suggestions = mock(SuggestionFeedback.class);
        when(suggestions.controlEntry(any(), any(), any(), any(), any(), any())).thenReturn(entry(FeedbackEventType.DISMISSED));
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        controls = new AttentionControlService(
            attention,
            ledger,
            mock(AttentionEventWriter.class),
            new AttentionProperties(null, null, null, null, 0, 0, null, null),
            suggestions,
            transactions,
            Clock.fixed(NOW, ZoneOffset.UTC));
    }


    @Test
    @DisplayName("숨김 사건을 남기지 못하면 숨기기 요청이 실패한다")
    void hideFailsWhenEventCannotBeWritten() {
        assertThatThrownBy(() -> controls.hide(USER, CardKey.FAILURES, ITEM, "state-a"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("쓰기 실패");
        verify(ledger).append(any(ControlEntry.class));
    }


    @Test
    @DisplayName("미루기 사건을 남기지 못하면 미루기 요청이 실패한다")
    void snoozeFailsWhenEventCannotBeWritten() {
        assertThatThrownBy(() -> controls.snooze(USER, CardKey.FAILURES, ITEM, NOW.plusSeconds(3_600)))
            .isInstanceOf(IllegalStateException.class);
    }


    @Test
    @DisplayName("복원 사건을 남기지 못하면 되돌리기 요청이 실패한다")
    void restoreFailsWhenEventCannotBeWritten() {
        assertThatThrownBy(() -> controls.restore(USER, CardKey.FAILURES, ITEM))
            .isInstanceOf(IllegalStateException.class);
        verify(ledger).append(any(ControlEntry.class));
    }


    @Test
    @DisplayName("마지막 제어가 이미 복원이면 되돌리기는 사건을 더하지 않고 성공한다")
    void restoreSkipsWhenAlreadyRestored() {
        when(ledger.latestControl(USER.id(), CardKey.FAILURES.name(), ITEM))
            .thenReturn(Optional.of(
                new ReactionRecord(5L, USER.id(), ITEM, null, FeedbackEventType.RESTORED, "ATTENTION_RESTORE", "FAILURES", null, NOW)));

        controls.restore(USER, CardKey.FAILURES, ITEM);

        verify(ledger, never()).append(any());
    }


    private static AttentionCandidate candidate() {
        AttentionCandidate candidate = mock(AttentionCandidate.class);
        when(candidate.itemKey()).thenReturn(ITEM);
        when(candidate.stateKey()).thenReturn("state-a");
        when(candidate.trigger()).thenReturn(AttentionTrigger.EXECUTION_FAILED);
        return candidate;
    }


    private static FeedbackEntry entry(FeedbackEventType type) {
        return FeedbackEntry.ofKey(USER.id(), FeedbackSubjectType.ATTENTION_ITEM, ITEM, type, FeedbackActor.USER, NOW);
    }
}
