package com.bifos.assistant.connector.application;

import com.bifos.assistant.chat.application.ChatContentMutationCoordinator;
import com.bifos.assistant.chat.application.ContentDraftBindingAccess;
import com.bifos.assistant.chat.application.ContentDraftSnapshotHash;
import com.bifos.assistant.chat.application.ContentDraftSnapshots;
import com.bifos.assistant.chat.application.model.ChatContentMutationTarget;
import com.bifos.assistant.chat.application.model.ContentDraftSnapshotInput;
import com.bifos.assistant.chat.domain.type.ContentDraftJobStatus;
import com.bifos.assistant.chat.infra.ContentDraftJobRepository;
import com.bifos.assistant.chat.infra.ContentDraftSnapshotRepository;
import com.bifos.assistant.connector.application.model.ContentDraftSaveTicketPayload;
import com.bifos.assistant.connector.application.model.PreparedContentDraftApproval;
import com.bifos.assistant.connector.domain.ConnectorAction;
import com.bifos.assistant.connector.domain.ToolPolicy;
import com.bifos.assistant.connector.domain.ToolPolicyDecision;
import com.bifos.assistant.connector.domain.ToolPolicyDecision.CheckBoundary;
import com.bifos.assistant.connector.domain.type.ActionDecision;
import com.bifos.assistant.connector.domain.type.ActionStatus;
import com.bifos.assistant.connector.domain.type.ConnectionStatus;
import com.bifos.assistant.connector.domain.type.ToolApproval;
import com.bifos.assistant.connector.infra.ConnectorActionRepository;
import com.bifos.assistant.hermes.dto.ConnectorManifest;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import com.bifos.assistant.chat.application.ConversationNotices;
import com.bifos.assistant.connector.application.model.ConnectorActionView;
import com.bifos.assistant.connector.domain.type.GrantPeriod;
import com.bifos.assistant.feedback.application.DecisionFeedbackRecorder;
import com.bifos.assistant.feedback.application.model.FeedbackEntry;
import com.bifos.assistant.feedback.domain.type.FeedbackActor;
import com.bifos.assistant.feedback.domain.type.FeedbackEventType;
import com.bifos.assistant.feedback.domain.type.FeedbackSubjectType;
import com.bifos.assistant.notification.application.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * 원고 snapshot 에 연결된 승인 줄의 승인이다. 승인 controller 가 job 링크로 고른 줄만 여기로 온다.
 *
 * <p>선택과 catalog 준비는 트랜잭션 밖에서, 현재 권한 검사와 jobId 예약은 coordinator 한 번 안에서 끝낸다. 예약을 커밋한 뒤에만
 * worker 시작을 최대 한 번 보낸다. 시작 응답을 잃으면 다시 보내지 않고 UNKNOWN 으로 둔다. 계약은
 * {@code docs/features/content-draft.md} 의 「승인과 jobId 예약」 이 갖는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContentDraftSaveApproval {
    /** 원고 저장 승인 줄이 정책을 빌려 오는 기존 새 글 임시저장 도구다. */
    static final String SAVE_TOOL = "save_draft";

    private final ConnectorActionRepository actions;
    private final ContentDraftJobRepository jobs;
    private final ContentDraftSnapshotRepository snapshotRows;
    private final ContentDraftSnapshots snapshots;
    private final ContentDraftBindingAccess bindings;
    private final ConnectorCatalogCache catalog;
    private final ChatContentMutationCoordinator mutations;
    private final ContentDraftJobLocks locks;
    private final ContentDraftSaveTicket tickets;
    private final ObjectProvider<ContentDraftJobStarter> starters;
    private final DecisionFeedbackRecorder feedback;
    private final ApplicationEventPublisher events;
    private final NotificationService notifications;
    private final ConversationNotices conversations;
    private final PlatformTransactionManager transactionManager;
    private final Clock clock;


    /**
     * 원고 job 링크가 있는 줄을 승인한다. 상시 허락은 주지 않는다. 일반 줄은 {@code ConnectorActionService} 가 맡는다.
     *
     * @return 거절·만료로 끝난 줄, 또는 시작을 보낸 뒤의 줄. 접수한 줄은 저장 결과를 확인할 때까지 EXECUTING 이다
     */
    public ConnectorActionView approve(CurrentUser user, UUID actionId, GrantPeriod grant) {
        if (grant != null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "this tool cannot be granted standing approval");
        }
        ConnectorAction finished = approvePrepared(user, prepare(user, actionId));
        feedback.record(FeedbackEntry.of(finished.userId(), FeedbackSubjectType.CONNECTOR_ACTION, finished.publicId(),
            FeedbackEventType.APPROVED, FeedbackActor.USER, now()).conversation(finished.conversationId())
            .originExecution(finished.originExecutionId()).version(finished.argsSha256()));
        new ConnectorActionSignals(events, notifications, conversations).publish(finished);
        return new ConnectorActionDetails(actions, catalog).view(finished);
    }


    /** 주인의 줄에 원고 job 링크가 있으면 그 snapshot 공개 ID 다. 인자 글이나 도구 이름으로 고르지 않는다. */
    public Optional<UUID> select(CurrentUser user, UUID actionId) {
        requireNoTransaction();
        return actions.findByPublicId(actionId)
            .filter(action -> action.userId().equals(user.id()))
            .flatMap(action -> jobs.findByActionId(action.id()))
            .flatMap(job -> snapshotRows.findById(job.snapshotId()))
            .map(snapshot -> snapshot.publicId());
    }


    /** DB 식별자와 불변 catalog 선언을 잠금 밖에서 준비한다. 준비하지 못한 값은 잠금 안에서 거절로 끝낸다. */
    public PreparedContentDraftApproval prepare(CurrentUser user, UUID actionId) {
        requireNoTransaction();
        var action = actions.findByPublicId(actionId).filter(found -> found.userId().equals(user.id()))
            .orElseThrow(ConnectorActionDetails::notFound);
        var job = jobs.findByActionId(action.id()).orElseThrow(ConnectorActionDetails::notFound);
        var snapshot = snapshotRows.findById(job.snapshotId()).orElseThrow(ConnectorActionDetails::notFound);
        List<Long> executionIds;
        try {
            executionIds = locks.executionIds(action.originExecutionId(), snapshot.originExecutionId());
        } catch (ContentDraftRefusal ex) {
            executionIds = List.of();
        }
        var stamp = attempt(() -> bindings.prepare(user, snapshot.agentId(), snapshot.bindingId()));
        var manifest = catalog.find(action.connectorId()).orElse(null);
        boolean supported = manifest != null && manifest.snapshot() != null
            && Boolean.TRUE.equals(manifest.snapshot().capabilities().get("start"))
            && Boolean.TRUE.equals(manifest.snapshot().capabilities().get("status"))
            && starters.getIfAvailable() != null;
        return new PreparedContentDraftApproval(actionId, job.id(), snapshot.id(), snapshot.publicId(),
            snapshot.conversationId(), snapshot.agentId(), executionIds, stamp, manifest, supported);
    }


    /** 바깥 트랜잭션 없이 coordinator 한 번으로 예약을 커밋하고, 커밋 뒤 시작을 최대 한 번 보낸다. */
    ConnectorAction approvePrepared(CurrentUser user, PreparedContentDraftApproval prepared) {
        requireNoTransaction();
        var target = new ChatContentMutationTarget(prepared.conversationId(), List.of());
        Reserved reserved;
        try {
            reserved = mutations.run(user.id(), target, () -> reserve(user, prepared));
        } catch (ContentDraftRefusal ex) {
            return mutations.run(user.id(), target, () -> refuse(user, prepared, ex.code()));
        }
        String ticket;
        JsonNode compiled;
        ContentDraftJobStarter starter;
        try {
            ticket = tickets.issue(reserved.ticket(), reserved.input().expiresAt());
            compiled = ContentDraftSnapshotHash.compile(reserved.input().canonicalDraft(), reserved.input().resolvedAssets());
            starter = starters.getObject();
        } catch (RuntimeException ex) {
            // 아직 시작을 보내지 않았다. 저장 클릭이 없으므로 실패로 끝낸다.
            return new TransactionTemplate(transactionManager).execute(status -> record(prepared, Outcome.rejected("CONTENT_DRAFT_JOB_UNAVAILABLE")));
        }
        Outcome outcome;
        try {
            outcome = starter.start(reserved.input().binding().profile(), reserved.input().binding().connectorId(), compiled, reserved.ticket(), ticket)
                .map(Outcome::rejected)
                .orElse(Outcome.ACCEPTED);
        } catch (RuntimeException ex) {
            // 보냈는지와 접수됐는지를 모른다. 다시 보내지 않는다. 예외 메시지에는 원격 응답이 섞일 수 있어 종류만 남긴다.
            log.warn("원고 저장 시작 결과를 알 수 없다 actionId={} kind={}", prepared.actionId(), ex.getClass().getSimpleName());
            outcome = Outcome.UNKNOWN;
        }
        Outcome finished = outcome;
        return new TransactionTemplate(transactionManager).execute(status -> record(prepared, finished));
    }


    /**
     * 기존 새 글 임시저장 정책을 지금 선언으로 판정한다. 원고 저장은 상시 허락을 보지 않고 늘 사람의 승인을 받는다.
     *
     * @return 거절이면 빈 값
     */
    static Optional<ToolPolicyDecision> decide(ConnectorManifest manifest, String argsJson) {
        Optional<ToolPolicy> closed = ConnectorToolPolicies.find(manifest, SAVE_TOOL).map(policy -> new ToolPolicy(policy.risk(),
            policy.approval() == ToolApproval.NONE ? ToolApproval.REQUIRED : policy.approval(), policy.title(), false, policy.identifiers()));
        var decision = ToolPolicyDecision.decide(ConnectionStatus.READY, true, manifest.schema(), closed, false,
            argsJson.getBytes(StandardCharsets.UTF_8).length, CheckBoundary.NOT_CHECK);
        if (decision.decision() == ActionDecision.DENIED) {
            return Optional.empty();
        }
        return Optional.of(new ToolPolicyDecision(ActionDecision.NEEDS_APPROVAL, null, decision.risk(), ToolApproval.ALWAYS));
    }


    private Reserved reserve(CurrentUser user, PreparedContentDraftApproval prepared) {
        if (prepared.executionIds().isEmpty()) {
            throw new ContentDraftRefusal("origin");
        }
        var locked = locks.lock(user, prepared.conversationId(), prepared.agentId(), prepared.executionIds(), prepared.actionId(),
            prepared.snapshotRowId(), prepared.jobRowId());
        var action = locked.action();
        if (action.status() != ActionStatus.PENDING || locked.job().status() != ContentDraftJobStatus.PREVIEW_READY) {
            throw notPending();
        }
        Instant now = now();
        if (!action.expiresAt().isAfter(now)) {
            throw new ContentDraftRefusal(ContentDraftRefusal.EXPIRED);
        }
        if (!prepared.startSupported() || prepared.stamp() == null || prepared.manifest() == null
            || decide(prepared.manifest(), action.argsJson()).isEmpty()) {
            throw new ContentDraftRefusal(ConnectorAction.NOT_EXECUTABLE);
        }
        ContentDraftSnapshotInput input;
        try {
            input = snapshots.requireCurrentSnapshotParticipant(user, prepared.conversationId(), prepared.snapshotId());
        } catch (ApiException ex) {
            throw new ContentDraftRefusal("stale");
        }
        // 준비한 catalog 정책 지문까지 preview 때와 같아야 한다. 현재 DB 판은 참가 함수가 확인했다.
        if (!input.binding().equals(prepared.stamp())) {
            throw new ContentDraftRefusal("stale");
        }
        UUID jobId = UUID.randomUUID();
        action.beginExecution(now);
        actions.save(action);
        var job = locked.job();
        job.approve(user.id(), now, jobId.toString());
        jobs.save(job);
        var snapshot = locked.snapshot();
        var stamp = input.binding();
        var payload = new ContentDraftSaveTicketPayload(jobId, snapshot.publicId(), snapshot.snapshotHash(), action.publicId(),
            user.id(), prepared.conversationId(), stamp.bindingId(), stamp.connectionId(), stamp.bindingUpdatedAt(),
            stamp.connectionUpdatedAt(), null, null);
        return new Reserved(jobId, input, payload);
    }


    /** 실행하지 않고 끝낸다. 외부 호출은 없다. */
    private ConnectorAction refuse(CurrentUser user, PreparedContentDraftApproval prepared, String code) {
        var action = actions.findByPublicIdForUpdate(prepared.actionId()).filter(found -> found.userId().equals(user.id()))
            .orElseThrow(ConnectorActionDetails::notFound);
        var job = jobs.lockById(prepared.jobRowId()).orElseThrow(ConnectorActionDetails::notFound);
        if (action.status() != ActionStatus.PENDING || job.status() != ContentDraftJobStatus.PREVIEW_READY) {
            throw notPending();
        }
        Instant now = now();
        if (ContentDraftRefusal.EXPIRED.equals(code)) {
            action.expire(now);
        } else {
            action.refuse(ConnectorAction.NOT_EXECUTABLE, now);
        }
        job.stale(code.equals("stale") ? ErrorCode.CONTENT_DRAFT_STALE.name() : ConnectorAction.NOT_EXECUTABLE);
        jobs.save(job);
        return actions.save(action);
    }


    /** 시작 결과를 적는다. 이미 다른 경로가 끝낸 상태는 덮지 않는다. */
    private ConnectorAction record(PreparedContentDraftApproval prepared, Outcome outcome) {
        Instant now = now();
        var action = actions.findByPublicIdForUpdate(prepared.actionId()).orElseThrow(ConnectorActionDetails::notFound);
        var job = jobs.lockById(prepared.jobRowId()).orElseThrow(ConnectorActionDetails::notFound);
        if (outcome.kind() == Outcome.Kind.ACCEPTED) {
            if (job.running()) {
                job.accepted(now);
            }
        } else if (outcome.kind() == Outcome.Kind.UNKNOWN) {
            if (job.status() == ContentDraftJobStatus.DISPATCHED) {
                job.unknown(now);
            }
            if (action.status() == ActionStatus.EXECUTING) {
                action.unknown(now);
            }
        } else if (job.running() && job.startClaimedAt() == null) {
            job.fail(outcome.errorCode(), now);
            if (action.status() == ActionStatus.EXECUTING) {
                action.fail("unavailable", null, now);
            }
        }
        jobs.save(job);
        return actions.save(action);
    }


    private static <T> T attempt(Supplier<T> work) {
        try {
            return work.get();
        } catch (ApiException ignored) {
            return null;
        }
    }


    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }


    private static void requireNoTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("content draft approval must start outside a transaction");
        }
    }


    private static ApiException notPending() {
        return new ApiException(ErrorCode.CONNECTOR_ACTION_NOT_PENDING, "this connector action is not waiting for approval");
    }


    private record Reserved(UUID jobId, ContentDraftSnapshotInput input, ContentDraftSaveTicketPayload ticket) {
    }

    private record Outcome(Kind kind, String errorCode) {
        enum Kind { ACCEPTED, REJECTED, UNKNOWN }

        static final Outcome ACCEPTED = new Outcome(Kind.ACCEPTED, null);
        static final Outcome UNKNOWN = new Outcome(Kind.UNKNOWN, null);


        static Outcome rejected(String errorCode) {
            return new Outcome(Kind.REJECTED, errorCode);
        }
    }
}
