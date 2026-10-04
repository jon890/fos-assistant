package com.bifos.assistant.followup.application;

import com.bifos.assistant.chat.application.ConversationAccess;
import com.bifos.assistant.chat.application.ConversationPublicIdLookup;
import com.bifos.assistant.followup.application.model.FollowUpPatch;
import com.bifos.assistant.followup.application.model.FollowUpProposalOutcome;
import com.bifos.assistant.followup.application.model.FollowUpSnapshot;
import com.bifos.assistant.followup.application.model.NewFollowUp;
import com.bifos.assistant.followup.domain.FollowUp;
import com.bifos.assistant.followup.domain.type.FollowUpStatus;
import com.bifos.assistant.followup.infra.FollowUpRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.shared.util.Sha256;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 사람이 할 일을 더하고 받아들이고 고치고 끝낸다. 뜻과 전이는 {@code docs/backend/follow-up.md} 가 갖는다(ADR-073).
 *
 * <p>쓰는 메서드에 {@code @Transactional} 을 붙이지 않는다. 같은 제목이 동시에 열려 유일 제약에 걸리면 그 예외를 트랜잭션 밖에서 잡아
 * 다시 읽어야 하는데, 메서드 트랜잭션 안에서 잡으면 트랜잭션이 rollback-only 로 남아 커밋할 때 실패한다.
 *
 * <p>제목은 로그에 남기지 않는다. 로그에는 사용자 번호, 할 일 번호, 동작만 적는다.
 */
@Slf4j
@Service
public class FollowUpService {

    /** {@code follow_up.title} 의 칸 길이다. 앞뒤 공백을 지운 글자 수로 센다. */
    public static final int TITLE_MAX = 200;

    /** 같은 대화에서 거절한 제목을 다시 받지 않는 기간이다. */
    static final Duration REJECTED_COOLDOWN = Duration.ofDays(30);

    /** 한 대화에 받아들이기를 기다리는 제안의 상한이다. */
    static final int MAX_OPEN_PROPOSALS_PER_CONVERSATION = 3;

    /** 한 실행이 제안할 수 있는 줄의 상한이다. */
    static final int MAX_PROPOSALS_PER_EXECUTION = 2;

    private static final Set<FollowUpStatus> LISTED = Set.of(FollowUpStatus.PROPOSED, FollowUpStatus.OPEN);

    private final FollowUpRepository followUps;
    private final ConversationAccess conversations;
    private final ConversationPublicIdLookup conversationIds;
    private final TransactionTemplate transactions;
    private final Clock clock;

    // 생성자를 직접 쓴다. 저장만 감싸는 트랜잭션을 여기서 만든다.
    public FollowUpService(
            FollowUpRepository followUps,
            ConversationAccess conversations,
            ConversationPublicIdLookup conversationIds,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.followUps = followUps;
        this.conversations = conversations;
        this.conversationIds = conversationIds;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * 같은 할 일인지 가르는 열쇠다. NFC 로 맞추고, 앞뒤 공백을 지우고, 연속 공백을 하나로 줄이고, 소문자로 바꾼 글의 SHA-256
     * 16진수다. 사람이 더하는 경로와 에이전트가 제안하는 경로가 모두 이 함수를 쓴다.
     */
    public static String titleKey(String title) {
        String normalized = Normalizer.normalize(title, Normalizer.Form.NFC)
                .strip()
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
        return Sha256.hex(normalized);
    }

    /** 끝나지 않은 할 일을 만든 순서로 낸다. */
    @Transactional(readOnly = true)
    public List<FollowUpSnapshot> list(CurrentUser user) {
        return openAndProposedOf(user.id());
    }

    /**
     * 그 사용자의 {@code PROPOSED} 와 {@code OPEN} 을 만든 순서로 낸다. 먼저 알리기의 판정이 읽는다. 연결한 대화를 지웠으면 그 줄의
     * 공개 식별자는 null 이다.
     */
    @Transactional(readOnly = true)
    public List<FollowUpSnapshot> openAndProposedOf(Long userId) {
        return snapshots(followUps.findByUserIdAndStatusInOrderByIdAsc(userId, LISTED));
    }

    /**
     * 사람이 직접 더한다. 바로 {@code OPEN} 이다.
     *
     * <p>같은 제목의 열린 줄이 있으면 새 줄을 만들지 않는다. 그 줄이 {@code PROPOSED} 면 받아들인 것으로 보고 {@code OPEN} 으로
     * 바꾼다. 같은 제목이 동시에 들어오면 유일 제약이 하나만 남기고, 진 쪽은 트랜잭션 밖에서 다시 읽어 그 줄을 돌려준다.
     */
    public FollowUpSnapshot create(CurrentUser user, NewFollowUp input) {
        String title = requireTitle(input.title());
        String key = titleKey(title);
        Long conversationId =
                input.conversationId() == null ? null : conversations.requireOwnId(user, input.conversationId());
        Instant now = clock.instant();
        Created result;
        try {
            result = transactions.execute(status -> openTitle(user, key, now)
                    .orElseGet(() -> new Created(
                            followUps.saveAndFlush(FollowUp.opened(
                                    user.id(), conversationId, title, key, input.dueAt(), input.waiting(), now)),
                            "created")));
        } catch (DataIntegrityViolationException ex) {
            // 같은 제목이 동시에 열렸다. 먼저 저장된 줄을 돌려준다.
            result = transactions.execute(status -> openTitle(user, key, now).orElseThrow(() -> ex));
        }
        log.info(
                "follow-up {} userId={} followUpId={}",
                result.action(),
                user.id(),
                result.followUp().publicId());
        return snapshot(result.followUp());
    }

    /**
     * 에이전트가 대화 중에 할 일을 제안한다. 새 줄은 {@code PROPOSED} 다. 규칙은 {@code docs/backend/follow-up.md} 의 「제안 억제」
     * 가 갖는다.
     *
     * <p>세는 것과 저장하는 것 사이에 잠금을 두지 않는다. 같은 대화에서 나란히 제안하면 상한을 조금 넘을 수 있다. 같은 제목이 동시에
     * 들어오면 유일 제약이 하나만 남기고, 진 쪽은 트랜잭션 밖에서 잡아 {@code DUPLICATE} 로 답한다.
     *
     * @param owner origin 실행의 사용자
     * @param conversationId origin 실행의 대화 번호
     * @param executionId 제안한 origin 실행의 번호
     * @param dueAt 기한. 없으면 null
     */
    public FollowUpProposalOutcome propose(
            CurrentUser owner, Long conversationId, Long executionId, String title, Instant dueAt, boolean waiting) {
        String stripped = requireTitle(title);
        String key = titleKey(stripped);
        Instant now = clock.instant();
        FollowUpProposalOutcome outcome;
        try {
            outcome = transactions.execute(status -> {
                if (followUps
                        .findByUserIdAndTitleKeyAndOpenMarker(owner.id(), key, FollowUp.OPEN_MARKER)
                        .isPresent()) {
                    return FollowUpProposalOutcome.DUPLICATE;
                }
                if (followUps.existsByConversationIdAndTitleKeyAndStatusAndClosedAtAfter(
                        conversationId, key, FollowUpStatus.REJECTED, now.minus(REJECTED_COOLDOWN))) {
                    return FollowUpProposalOutcome.DECLINED_BEFORE;
                }
                if (followUps.countByConversationIdAndStatus(conversationId, FollowUpStatus.PROPOSED)
                        >= MAX_OPEN_PROPOSALS_PER_CONVERSATION) {
                    return FollowUpProposalOutcome.TOO_MANY_PROPOSALS;
                }
                if (followUps.countByProposedByExecutionId(executionId) >= MAX_PROPOSALS_PER_EXECUTION) {
                    return FollowUpProposalOutcome.TOO_MANY_IN_RUN;
                }
                followUps.saveAndFlush(
                        FollowUp.proposed(owner.id(), conversationId, executionId, stripped, key, dueAt, waiting, now));
                return FollowUpProposalOutcome.CREATED;
            });
        } catch (DataIntegrityViolationException ex) {
            // 같은 제목이 동시에 열렸다. 먼저 저장된 줄이 남는다.
            outcome = FollowUpProposalOutcome.DUPLICATE;
        }
        log.info("follow-up proposal userId={} executionId={} outcome={}", owner.id(), executionId, outcome);
        return outcome;
    }

    /**
     * 끝나지 않은 줄의 제목, 기한, 기다리는 중을 고친다. 바꾼 제목이 같은 사용자의 다른 열린 줄과 같으면 409 다.
     */
    public FollowUpSnapshot update(CurrentUser user, UUID id, FollowUpPatch patch) {
        String newTitle = patch.title() == null ? null : requireTitle(patch.title());
        Instant now = clock.instant();
        FollowUp saved;
        try {
            saved = transactions.execute(status -> {
                FollowUp followUp = requireOwn(user, id);
                if (!followUp.status().open()) {
                    throw notInState();
                }
                String title = newTitle == null ? followUp.title() : newTitle;
                String key = newTitle == null ? followUp.titleKey() : titleKey(newTitle);
                if (!key.equals(followUp.titleKey())) {
                    followUps
                            .findByUserIdAndTitleKeyAndOpenMarker(user.id(), key, FollowUp.OPEN_MARKER)
                            .filter(other -> !other.id().equals(followUp.id()))
                            .ifPresent(other -> {
                                throw sameTitleOpen();
                            });
                }
                Instant dueAt = patch.dueAtPresent() ? patch.dueAt() : followUp.dueAt();
                boolean waiting = patch.waiting() == null ? followUp.waiting() : patch.waiting();
                followUp.revise(title, key, dueAt, waiting, now);
                return followUps.saveAndFlush(followUp);
            });
        } catch (DataIntegrityViolationException ex) {
            // 고치는 사이에 같은 제목의 줄이 열렸다.
            throw new ApiException(ErrorCode.FOLLOW_UP_STATE_CONFLICT, "another open follow-up has this title", ex);
        }
        log.info("follow-up updated userId={} followUpId={}", user.id(), saved.publicId());
        return snapshot(saved);
    }

    /** 제안을 받아들인다. {@code PROPOSED} 만 받는다. */
    public FollowUpSnapshot accept(CurrentUser user, UUID id) {
        return transition(user, id, "accepted", FollowUpStatus.PROPOSED, FollowUp::accept);
    }

    /** 제안을 거절한다. {@code PROPOSED} 만 받는다. */
    public FollowUpSnapshot reject(CurrentUser user, UUID id) {
        return transition(user, id, "rejected", FollowUpStatus.PROPOSED, FollowUp::reject);
    }

    /** 끝낸다. {@code OPEN} 만 받는다. */
    public FollowUpSnapshot done(CurrentUser user, UUID id) {
        return transition(user, id, "done", FollowUpStatus.OPEN, FollowUp::done);
    }

    /** 그만둔다. {@code OPEN} 만 받는다. */
    public FollowUpSnapshot drop(CurrentUser user, UUID id) {
        return transition(user, id, "dropped", FollowUpStatus.OPEN, FollowUp::drop);
    }

    private FollowUpSnapshot transition(
            CurrentUser user, UUID id, String action, FollowUpStatus from, BiConsumer<FollowUp, Instant> move) {
        Instant now = clock.instant();
        FollowUp saved = transactions.execute(status -> {
            FollowUp followUp = requireOwn(user, id);
            if (followUp.status() != from) {
                throw notInState();
            }
            move.accept(followUp, now);
            return followUps.saveAndFlush(followUp);
        });
        log.info("follow-up {} userId={} followUpId={}", action, user.id(), saved.publicId());
        return snapshot(saved);
    }

    /**
     * 같은 제목의 열린 줄을 찾아, {@code PROPOSED} 면 받아들인다. 트랜잭션 안에서 부른다.
     *
     * <p>동작은 그 줄을 그대로 돌려줬으면 {@code reused}, 받아들였으면 {@code accepted} 다.
     */
    private Optional<Created> openTitle(CurrentUser user, String key, Instant now) {
        return followUps
                .findByUserIdAndTitleKeyAndOpenMarker(user.id(), key, FollowUp.OPEN_MARKER)
                .map(existing -> {
                    if (existing.status() != FollowUpStatus.PROPOSED) {
                        return new Created(existing, "reused");
                    }
                    existing.accept(now);
                    return new Created(followUps.saveAndFlush(existing), "accepted");
                });
    }

    /**
     * 더하기가 돌려줄 줄과 로그에 적을 동작이다.
     *
     * @param action 새로 만들었으면 {@code created}, 같은 열린 줄을 돌려줬으면 {@code reused}, 제안을 받아들였으면
     *     {@code accepted}
     */
    private record Created(FollowUp followUp, String action) {}

    /** 주인의 할 일을 잠그고 읽는다. 같은 줄을 바꾸는 요청이 서로 덮지 않게 한다. 트랜잭션 안에서 부른다. */
    private FollowUp requireOwn(CurrentUser user, UUID id) {
        return followUps
                .findByPublicIdAndUserIdForUpdate(id, user.id())
                .orElseThrow(() -> new ApiException(ErrorCode.FOLLOW_UP_NOT_FOUND, "no such follow-up"));
    }

    /** 앞뒤 공백을 지운 제목이다. 비었거나 {@link #TITLE_MAX} 자를 넘으면 400 이다. */
    private static String requireTitle(String title) {
        String stripped = title == null ? "" : title.strip();
        int length = stripped.codePointCount(0, stripped.length());
        if (length < 1 || length > TITLE_MAX) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "title must be 1 to 200 characters");
        }
        return stripped;
    }

    private static ApiException notInState() {
        return new ApiException(ErrorCode.FOLLOW_UP_STATE_CONFLICT, "follow-up is not in a state for this action");
    }

    private static ApiException sameTitleOpen() {
        return new ApiException(ErrorCode.FOLLOW_UP_STATE_CONFLICT, "another open follow-up has this title");
    }

    private FollowUpSnapshot snapshot(FollowUp followUp) {
        return snapshots(List.of(followUp)).getFirst();
    }

    /** 대화 번호를 한 번에 공개 식별자로 바꾼다. 지운 대화는 빠지므로 그 줄의 공개 식별자는 null 이다. */
    private List<FollowUpSnapshot> snapshots(List<FollowUp> rows) {
        Map<Long, UUID> publicIds = conversationIds.activePublicIdsOf(rows.stream()
                .map(FollowUp::conversationId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet()));
        return rows.stream()
                .map(row -> new FollowUpSnapshot(
                        row.publicId(),
                        row.conversationId(),
                        row.conversationId() == null ? null : publicIds.get(row.conversationId()),
                        row.title(),
                        row.dueAt(),
                        row.waiting(),
                        row.status(),
                        row.proposedByAgent(),
                        row.createdAt(),
                        row.updatedAt(),
                        row.acceptedAt(),
                        row.closedAt()))
                .toList();
    }
}
