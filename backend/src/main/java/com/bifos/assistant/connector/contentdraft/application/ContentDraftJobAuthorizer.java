package com.bifos.assistant.connector.contentdraft.application;

import com.bifos.assistant.chat.attachment.application.ChatContentMutationCoordinator;
import com.bifos.assistant.chat.contentdraft.application.ContentDraftSnapshots;
import com.bifos.assistant.chat.attachment.application.dto.ChatContentMutationTarget;
import com.bifos.assistant.chat.contentdraft.domain.type.ContentDraftJobStatus;
import com.bifos.assistant.chat.contentdraft.infra.ContentDraftJobRepository;
import com.bifos.assistant.chat.contentdraft.infra.ContentDraftSnapshotRepository;
import com.bifos.assistant.connector.contentdraft.application.dto.ContentDraftSaveTicketPayload;
import com.bifos.assistant.connector.action.domain.type.ActionStatus;
import com.bifos.assistant.connector.action.infra.ConnectorActionRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 원고 저장 worker 가 시작, 사진 전송, 저장 클릭 직전에 묻는 현재 권한 확인이다.
 *
 * <p>매번 ticket 서명과 기한, 예약 job, 승인, 사용자 허용, 에이전트, origin·루트 실행 취소, 현재 revision·바인딩·원본을 잠금 안에서
 * 확인한다. 시작과 저장은 각각 NULL 에서 시각으로 바꾸는 갱신 한 번만 허가하고, 그 갱신과 같은 트랜잭션을 커밋한 뒤에 허가를
 * 돌려준다. 거절은 본문 없는 고정 오류다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContentDraftJobAuthorizer {
    public static final String START = "start";
    public static final String PHOTO = "photo";
    public static final String SAVE = "save";

    private final ContentDraftSaveTicket tickets;
    private final ContentDraftJobRepository jobs;
    private final ContentDraftSnapshotRepository snapshotRows;
    private final ConnectorActionRepository actions;
    private final AppUserRepository users;
    private final ContentDraftSnapshots snapshots;
    private final ChatContentMutationCoordinator mutations;
    private final ContentDraftJobLocks locks;
    private final Clock clock;


    /**
     * @param position 사진 단계에서 전송할 canonical block 위치. 다른 단계에서는 null
     * @throws ApiException 서명·형식이 틀리면 401, 그 밖의 거절은 403
     */
    public void authorize(String ticket, String stage, Integer position) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        var payload = tickets.authenticate(ticket);
        boolean photo = PHOTO.equals(stage);
        if (!(START.equals(stage) || SAVE.equals(stage) || photo) || photo != (position != null)
            || (photo && (position < 0 || position > 499))) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "content save authorize request rejected");
        }
        try {
            var job = jobs.findByJobId(payload.jobId().toString()).orElseThrow(ContentDraftJobAuthorizer::denied);
            var snapshot = snapshotRows.findById(job.snapshotId()).orElseThrow(ContentDraftJobAuthorizer::denied);
            var action = actions.findById(job.actionId()).orElseThrow(ContentDraftJobAuthorizer::denied);
            require(snapshot.publicId().equals(payload.snapshotId()) && snapshot.snapshotHash().equals(payload.snapshotHash())
                && action.publicId().equals(payload.actionId()) && payload.ownerUserId().equals(snapshot.ownerUserId())
                && payload.conversationId().equals(snapshot.conversationId()) && payload.bindingId().equals(snapshot.bindingId())
                && payload.connectionId().equals(snapshot.connectionId())
                && payload.bindingUpdatedAt().equals(snapshot.bindingUpdatedAt())
                && payload.connectionUpdatedAt().equals(snapshot.connectionUpdatedAt()));
            var owner = users.findById(payload.ownerUserId()).orElseThrow(ContentDraftJobAuthorizer::denied);
            var user = new CurrentUser(owner.id(), owner.email(), owner.displayName(), owner.groupId(), owner.role());
            var executionIds = locks.executionIds(action.originExecutionId(), snapshot.originExecutionId());
            mutations.run(user.id(), new ChatContentMutationTarget(snapshot.conversationId(), List.of()), 5, () -> {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void beforeCommit(boolean readOnly) {
                        // DB 잠금 뒤에도 worker의 응답 한도와 ticket 기한을 넘긴 claim은 커밋하지 않는다.
                        require(System.nanoTime() < deadline);
                        tickets.authenticate(ticket);
                    }
                });
                claim(user, payload, stage, position, executionIds, job.id(), snapshot.id(), snapshot.agentId());
                return null;
            });
        } catch (RuntimeException ex) {
            // 어느 검사에서 거절했는지 worker 에 알리지 않는다. 로그에도 ticket 과 본문을 남기지 않는다.
            log.info("원고 저장 허가를 거절했다 stage={} kind={}", stage, ex.getClass().getSimpleName());
            throw denied();
        }
    }


    private void claim(CurrentUser user, ContentDraftSaveTicketPayload payload, String stage, Integer position,
        List<Long> executionIds, Long jobRowId, Long snapshotRowId, Long agentId) {
        var locked = locks.lock(user, payload.conversationId(), agentId, executionIds, payload.actionId(), snapshotRowId, jobRowId);
        var action = locked.action();
        var job = locked.job();
        // 시작 응답을 잃으면 승인 줄은 UNKNOWN 이다. 승인 사실은 그대로다. 일반 실행 정리는 원고 승인 줄을 바꾸지 않는다.
        require((action.status() == ActionStatus.EXECUTING || action.status() == ActionStatus.UNKNOWN) && action.decidedAt() != null);
        require(payload.jobId().toString().equals(job.jobId()) && user.id().equals(job.approvedBy()) && job.approvedAt() != null
            && (job.running() || job.status() == ContentDraftJobStatus.UNKNOWN));
        var input = snapshots.requireCurrentSnapshotParticipant(user, payload.conversationId(), payload.snapshotId());
        var binding = input.binding();
        require(Objects.equals(binding.bindingId(), payload.bindingId()) && Objects.equals(binding.connectionId(), payload.connectionId())
            && binding.bindingUpdatedAt().equals(payload.bindingUpdatedAt())
            && binding.connectionUpdatedAt().equals(payload.connectionUpdatedAt()));
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        switch (stage) {
            case START -> require(jobs.claimStart(jobRowId, now) == 1);
            case SAVE -> require(jobs.claimSave(jobRowId, now) == 1);
            default -> {
                var blocks = input.canonicalDraft().path("blocks");
                require(job.startClaimedAt() != null && job.saveClaimedAt() == null && IntStream.range(0, blocks.size())
                    .filter(index -> "image".equals(blocks.get(index).path("type").stringValue())).anyMatch(index -> index == position));
            }
        }
    }


    private static void require(boolean condition) {
        if (!condition) {
            throw denied();
        }
    }


    private static ApiException denied() {
        return new ApiException(ErrorCode.FORBIDDEN, "content save not allowed");
    }
}
