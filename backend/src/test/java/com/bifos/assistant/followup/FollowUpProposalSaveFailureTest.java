package com.bifos.assistant.followup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bifos.assistant.chat.application.ConversationAccess;
import com.bifos.assistant.chat.application.ConversationPublicIdLookup;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.followup.application.FollowUpService;
import com.bifos.assistant.followup.application.model.FollowUpProposalOutcome;
import com.bifos.assistant.followup.domain.FollowUp;
import com.bifos.assistant.followup.infra.FollowUpRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 제안을 저장하다 실패했을 때 다시 읽어 같은 할 일인지 가리는지 본다. 동시 삽입의 교착은 H2 에서 재현되지 않으므로 저장소를 대역으로 둔다.
 */
class FollowUpProposalSaveFailureTest {

    private static final Instant NOW = Instant.parse("2026-10-05T09:00:00Z");
    private static final String TITLE = "할 일 검사 7391";
    private static final CurrentUser DAD = new CurrentUser(7L, "dad@example.com", "아빠", 1L, UserRole.MEMBER);

    private FollowUpRepository repository;
    private FollowUpService followUps;

    @BeforeEach
    void setUp() {
        repository = mock(FollowUpRepository.class);
        ConversationAccess conversations = mock(ConversationAccess.class);
        when(conversations.requireOwn(DAD, 11L))
                .thenReturn(Conversation.startedBy(DAD.id(), "대화", 1L, NOW));
        followUps = new FollowUpService(
                repository,
                conversations,
                mock(ConversationPublicIdLookup.class),
                mock(PlatformTransactionManager.class),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("저장이 교착으로 끊겨도 다시 읽어 같은 제목의 줄이 있으면 DUPLICATE 다")
    void answersDuplicateWhenLockFailureLeavesSameTitleRow() {
        FollowUp winner =
                FollowUp.proposed(DAD.id(), 11L, 22L, TITLE, FollowUpService.titleKey(TITLE), null, false, NOW);
        when(repository.findByUserIdAndTitleKeyAndOpenMarker(anyLong(), anyString(), any()))
                .thenReturn(Optional.empty(), Optional.of(winner));
        when(repository.saveAndFlush(any())).thenThrow(new CannotAcquireLockException("deadlock"));

        FollowUpProposalOutcome outcome = followUps.propose(DAD, 11L, 33L, TITLE, null, false);

        assertThat(outcome).isEqualTo(FollowUpProposalOutcome.DUPLICATE);
    }

    @Test
    @DisplayName("유일 제약에 걸렸는데 다시 읽어도 같은 제목의 줄이 없으면 저장 실패를 그대로 던진다")
    void rethrowsWhenNoSameTitleRowAfterIntegrityViolation() {
        DataIntegrityViolationException failure = new DataIntegrityViolationException("public_id");
        when(repository.findByUserIdAndTitleKeyAndOpenMarker(anyLong(), anyString(), any()))
                .thenReturn(Optional.empty());
        when(repository.saveAndFlush(any())).thenThrow(failure);

        assertThatThrownBy(() -> followUps.propose(DAD, 11L, 33L, TITLE, null, false))
                .isSameAs(failure);
    }
}
