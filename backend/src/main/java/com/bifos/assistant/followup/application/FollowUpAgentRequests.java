package com.bifos.assistant.followup.application;

import com.bifos.assistant.feedback.application.DecisionFeedbackRecorder;
import com.bifos.assistant.feedback.domain.type.FeedbackActor;
import com.bifos.assistant.feedback.domain.type.FeedbackEventType;
import com.bifos.assistant.followup.application.dto.FollowUpFinish;
import com.bifos.assistant.followup.application.dto.FollowUpProposalOutcome;
import com.bifos.assistant.followup.application.dto.FollowUpSnapshot;
import com.bifos.assistant.followup.domain.FollowUp;
import com.bifos.assistant.followup.domain.type.FollowUpStatus;
import com.bifos.assistant.followup.infra.FollowUpRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 사용자가 직접 시킨 할 일을 에이전트가 바로 만들고 끝낸다(서비스 개념 스펙 REQ11, REQ12). 사용자가 시킨 실행인지는 부르는 쪽(mcp)이
 * 판정하고, 여기서는 요청자 자신의 줄만 다룬다.
 *
 * <p>제목은 로그에 남기지 않는다.
 */
@Slf4j
@Service
public class FollowUpAgentRequests {

    /** 한 실행이 바로 만들 수 있는 할 일의 상한이다. 바깥 글에 숨은 지시가 할 일을 끝없이 만들지 못하게 한다. */
    static final int MAX_DIRECT_PER_EXECUTION = 10;

    private final FollowUpRepository followUps;
    private final FollowUpService service;
    private final DecisionFeedbackRecorder feedback;
    private final TransactionTemplate transactions;
    private final Clock clock;


    // 생성자를 직접 쓴다. 저장만 감싸는 트랜잭션을 여기서 만든다.
    public FollowUpAgentRequests(
        FollowUpRepository followUps,
        FollowUpService service,
        DecisionFeedbackRecorder feedback,
        PlatformTransactionManager transactionManager,
        Clock clock) {
        this.followUps = followUps;
        this.service = service;
        this.feedback = feedback;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }


    /**
     * 바로 챙기는 {@code OPEN} 줄을 만든다. 같은 제목의 제안이 있으면 받아들이고, 이미 챙기는 줄이면 새로 만들지 않는다.
     *
     * <p>제안의 억제 규칙(거절한 제목, 대화의 제안 상한)은 쓰지 않는다. 사용자가 직접 시킨 일이다. 같은 제목이 동시에 열려 유일 제약에
     * 걸리면 트랜잭션 밖에서 다시 읽는다.
     *
     * @param executionId 만든 origin 실행의 번호
     */
    public FollowUpProposalOutcome openNow(
        CurrentUser owner, Long conversationId, Long executionId, String title, Instant dueAt, boolean waiting) {
        String stripped = FollowUpService.requireTitle(title);
        String key = FollowUpService.titleKey(stripped);
        Instant now = clock.instant();
        FollowUpProposalOutcome outcome;
        try {
            outcome = transactions.execute(status -> existing(owner, key, now).orElseGet(() -> {
                if (followUps.countByProposedByExecutionId(executionId) >= MAX_DIRECT_PER_EXECUTION) {
                    return FollowUpProposalOutcome.TOO_MANY_IN_RUN;
                }
                FollowUp saved = followUps.saveAndFlush(
                    FollowUp.openedByAgent(owner.id(), conversationId, executionId, stripped, key, dueAt, waiting, now));
                feedback.record(FollowUpService.feedbackOf(saved, FeedbackEventType.SURFACED, FeedbackActor.AGENT, now));
                feedback.record(FollowUpService.feedbackOf(saved, FeedbackEventType.ACCEPTED, FeedbackActor.USER, now)
                    .reason("USER_REQUESTED"));
                return FollowUpProposalOutcome.OPENED;
            }));
        } catch (DataIntegrityViolationException | PessimisticLockingFailureException ex) {
            // 같은 제목이 동시에 열렸다. 먼저 저장된 줄이 있을 때만 그 줄로 답한다.
            outcome = transactions.execute(status -> existing(owner, key, now).orElseThrow(() -> ex));
        }
        log.info("follow-up direct open userId={} executionId={} outcome={}", owner.id(), executionId, outcome);
        return outcome;
    }


    /** 같은 제목의 열린 줄이 있으면 제안은 받아들이고 그 결과를 낸다. 트랜잭션 안에서 부른다. */
    private Optional<FollowUpProposalOutcome> existing(CurrentUser owner, String key, Instant now) {
        return service.acceptOpenTitle(owner, key, now)
            .map(accepted -> accepted ? FollowUpProposalOutcome.OPENED : FollowUpProposalOutcome.DUPLICATE);
    }


    /** 요청자가 챙기는({@code OPEN}) 할 일이다. 받아들이지 않은 제안은 내지 않는다. */
    public List<FollowUpSnapshot> openOf(CurrentUser user) {
        return service.list(user).stream().filter(row -> row.status() == FollowUpStatus.OPEN).toList();
    }


    /**
     * 챙기는 할 일 하나를 끝낸다. {@code ref} 는 번호나 제목이다. 번호가 맞으면 그 줄, 아니면 제목이 같은 줄, 그것도 없으면 제목에 그 글이
     * 든 줄이다. 여럿이 맞으면 바꾸지 않고 후보를 낸다. 남의 줄은 없는 것과 같다.
     */
    public FollowUpFinish finish(CurrentUser user, String ref) {
        List<FollowUpSnapshot> matches = match(openOf(user), ref);
        if (matches.size() != 1) {
            return new FollowUpFinish(
                matches.isEmpty() ? FollowUpFinish.Status.NOT_FOUND : FollowUpFinish.Status.AMBIGUOUS, matches);
        }
        try {
            return new FollowUpFinish(FollowUpFinish.Status.DONE, List.of(service.done(user, matches.getFirst().publicId())));
        } catch (ApiException ex) {
            if (ex.code() != ErrorCode.FOLLOW_UP_NOT_FOUND && ex.code() != ErrorCode.FOLLOW_UP_STATE_CONFLICT) {
                throw ex;
            }
            // 찾은 뒤 그 사이에 화면에서 끝냈거나 지웠다.
            return new FollowUpFinish(FollowUpFinish.Status.NOT_FOUND, List.of());
        }
    }


    private static List<FollowUpSnapshot> match(List<FollowUpSnapshot> rows, String ref) {
        String stripped = ref.strip();
        Optional<UUID> id = uuid(stripped);
        if (id.isPresent()) {
            return rows.stream().filter(row -> row.publicId().equals(id.get())).toList();
        }
        String wanted = normalized(stripped);
        List<FollowUpSnapshot> same = rows.stream().filter(row -> normalized(row.title()).equals(wanted)).toList();
        if (!same.isEmpty() || wanted.isEmpty()) {
            return same;
        }
        return rows.stream().filter(row -> normalized(row.title()).contains(wanted)).toList();
    }


    private static Optional<UUID> uuid(String text) {
        try {
            return Optional.of(UUID.fromString(text));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }


    /** {@link FollowUpService#titleKey} 와 같은 정규화다. */
    private static String normalized(String title) {
        return Normalizer.normalize(title, Normalizer.Form.NFC).strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
