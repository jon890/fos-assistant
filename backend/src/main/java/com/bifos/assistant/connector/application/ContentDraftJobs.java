package com.bifos.assistant.connector.application;

import com.bifos.assistant.chat.application.ChatContentMutationCoordinator;
import com.bifos.assistant.chat.application.ContentDraftBindingAccess;
import com.bifos.assistant.chat.application.ContentDraftJobQueries;
import com.bifos.assistant.chat.application.ContentDraftSaveRequests;
import com.bifos.assistant.chat.application.ContentDraftSnapshotHash;
import com.bifos.assistant.chat.application.ContentDraftSnapshotInputs;
import com.bifos.assistant.chat.application.ContentDraftSnapshots;
import com.bifos.assistant.chat.application.ConversationAccess;
import com.bifos.assistant.chat.application.model.ChatContentMutationTarget;
import com.bifos.assistant.chat.application.model.ContentDraftJobPage;
import com.bifos.assistant.chat.application.model.ContentDraftJobView;
import com.bifos.assistant.chat.application.model.ContentDraftSaveRequestView;
import com.bifos.assistant.chat.domain.entity.ContentDraft;
import com.bifos.assistant.chat.domain.entity.ContentDraftJob;
import com.bifos.assistant.chat.domain.entity.ContentDraftRevision;
import com.bifos.assistant.chat.domain.entity.ContentDraftSnapshot;
import com.bifos.assistant.chat.domain.type.ContentDraftJobStatus;
import com.bifos.assistant.chat.infra.ContentDraftJobRepository;
import com.bifos.assistant.chat.infra.ContentDraftRepository;
import com.bifos.assistant.chat.infra.ContentDraftRevisionRepository;
import com.bifos.assistant.chat.infra.ContentDraftSnapshotRepository;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.connector.application.model.ContentDraftActionSummary;
import com.bifos.assistant.connector.application.model.ConnectorActionChanged;
import com.bifos.assistant.connector.application.model.ContentDraftSaveTicketPayload;
import com.bifos.assistant.chat.application.model.ContentDraftBindingStamp;
import com.bifos.assistant.hermes.ContentDraftJobNotFound;
import com.bifos.assistant.hermes.HttpContentDraftSnapshotClient;
import com.bifos.assistant.connector.application.model.ContentDraftWorkerStatus;
import com.bifos.assistant.connector.domain.ConnectorAction;
import com.bifos.assistant.connector.domain.type.ActionStatus;
import com.bifos.assistant.connector.infra.ConnectorActionRepository;
import com.bifos.assistant.connector.infra.ConnectorActionBodies;
import com.bifos.assistant.connector.infra.ConnectorBindingRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.config.LiveProperties;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.shared.util.Sha256;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 원고 snapshot 의 저장 요청과 보관한 job 조회를 맡는다. chat 의 두 port 를 구현한다.
 *
 * <p>저장 요청은 기존 승인 줄 하나와 job 링크 하나를 같은 트랜잭션에서 만든다. 같은 snapshot 의 두 번째 요청은 앞의 줄을 그대로
 * 돌려준다. 현재 연결이 같은 조회만 예약한 private job을 읽고 시작은 재전송하지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContentDraftJobs implements ContentDraftSaveRequests, ContentDraftJobQueries {
    static final int MAX_PAGE = 20;
    private static final String DEDUPE_PREFIX = "v1-content-save";
    /** MCP 서버의 등록 이름이 아니므로 일반 승인 경로는 이 줄을 선언 없는 도구로 보고 실행 없이 거절한다. */
    static final String HERMES_TOOL = "fos_content_draft_save";
    /** 시작 전송의 응답 한도(60초)보다 넉넉히 길다. 그 안의 DISPATCHED job 은 worker 가 아직 모를 수 있다. */
    private static final Duration START_WINDOW = Duration.ofMinutes(2);
    private static final Set<ContentDraftJobStatus> RUNNING = EnumSet.of(ContentDraftJobStatus.DISPATCHED, ContentDraftJobStatus.SAVING);

    private final ContentDraftSnapshots snapshots;
    private final ContentDraftSnapshotInputs inputs;
    private final ContentDraftBindingAccess bindings;
    private final ChatContentMutationCoordinator mutations;
    private final ConversationAccess access;
    private final ContentDraftJobRepository jobs;
    private final ContentDraftSnapshotRepository snapshotRows;
    private final ContentDraftRevisionRepository revisions;
    private final ContentDraftRepository drafts;
    private final ConnectorActionRepository actions;
    private final ConnectorActionBodies bodies;
    private final ConnectorBindingRepository bindingRows;
    private final ConnectorCatalogCache catalog;
    private final AgentExecutionRepository executions;
    private final LiveProperties<ConnectorPolicyProperties> policy;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final HttpContentDraftSnapshotClient worker;
    private final AppUserRepository owners;
    private final ConversationRepository conversations;


    @Override
    public ContentDraftSaveRequestView requestSave(CurrentUser user, Long originExecutionId, UUID snapshotId) {
        var snapshot = snapshotRows.findByPublicIdAndOwnerUserId(snapshotId, user.id()).orElseThrow(ContentDraftJobs::notFound);
        Long conversationId = snapshot.conversationId();
        // 지금 호출한 실행이 같은 사용자·대화의 취소되지 않은 실행이고 snapshot 의 에이전트와 같아야 한다.
        if (!snapshot.agentId().equals(inputs.agent(user, conversationId, originExecutionId))) {
            throw ContentDraftSnapshotHash.unavailable("save:other-agent");
        }
        var existing = jobs.findBySnapshotId(snapshot.id());
        if (existing.isPresent()) {
            return requestView(snapshot, existing.get());
        }
        var stamp = bindings.prepare(user, snapshot.agentId(), snapshot.bindingId());
        var manifest = catalog.find(stamp.connectorId()).orElseThrow(() -> ContentDraftSnapshotHash.unavailable("save:catalog-missing"));
        String argsJson = "{\"snapshotId\":\"" + snapshotId + "\"}";
        var decision = ContentDraftSaveApproval.decide(manifest, argsJson)
            .orElseThrow(() -> ContentDraftSnapshotHash.unavailable("save:approval-undecided"));
        var inserted = new AtomicBoolean();
        try {
            var created = mutations.run(user.id(), new ChatContentMutationTarget(conversationId, List.of()), () -> {
                var input = snapshots.requireCurrentSnapshotParticipant(user, conversationId, snapshotId);
                if (!input.binding().equals(stamp)) {
                    throw ContentDraftSnapshotInputs.stale();
                }
                var again = jobs.findBySnapshotId(snapshot.id());
                if (again.isPresent()) {
                    return requestView(snapshot, again.get());
                }
                Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
                var origin = executions.findById(originExecutionId).orElseThrow(() -> ContentDraftSnapshotHash.unavailable("origin:not-found"));
                var connection = bindingRows.findById(snapshot.bindingId())
                    .orElseThrow(() -> ContentDraftSnapshotHash.unavailable("binding:not-found")).connection();
                var action = ConnectorAction.decided(connection, snapshot.agentId(), origin,
                    HERMES_TOOL, ContentDraftSaveApproval.SAVE_TOOL,
                    decision, false, Sha256.hex(DEDUPE_PREFIX + ":" + user.id() + ":" + snapshotId), Sha256.hex(argsJson), now);
                action.awaitApproval(now.plus(policy.current().approvalTtl()));
                var saved = actions.saveAndFlush(action);
                bodies.sealArgs(saved, argsJson);
                var job = jobs.saveAndFlush(new ContentDraftJob(snapshot.id(), saved.id(), user.id(), conversationId, now));
                inserted.set(true);
                return requestView(snapshot, job);
            });
            // 사건은 커밋한 뒤에 낸다. 받은 쪽이 읽을 때 승인 줄이 있어야 한다.
            if (inserted.get()) {
                events.publishEvent(new ConnectorActionChanged(conversationId, created.actionId()));
            }
            return created;
        } catch (DataIntegrityViolationException ex) {
            return jobs.findBySnapshotId(snapshot.id()).map(job -> requestView(snapshot, job)).orElseThrow(() -> ex);
        }
    }


    @Override
    public ContentDraftJobView refresh(CurrentUser user, Long conversationId, UUID draftId, UUID snapshotId) {
        access.requireOwn(user, conversationId);
        var snapshot = snapshotRows.findByPublicIdAndOwnerUserIdAndConversationId(snapshotId, user.id(), conversationId)
            .orElseThrow(ContentDraftJobs::notFound);
        var job = jobs.findBySnapshotId(snapshot.id()).orElseThrow(ContentDraftJobs::notFound);
        var view = views(List.of(job)).getFirst();
        if (!draftId.equals(view.draftId())) {
            throw notFound();
        }
        return refreshJob(user, snapshot, job, view);
    }


    private ContentDraftJobView refreshJob(CurrentUser user, ContentDraftSnapshot snapshot, ContentDraftJob job, ContentDraftJobView view) {
        Long conversationId = snapshot.conversationId();
        if (job.jobId() == null || !job.awaitingResult()) {
            return view;
        }
        ContentDraftBindingStamp binding;
        try {
            binding = bindings.prepare(user, snapshot.agentId(), snapshot.bindingId());
        } catch (ApiException ignored) {
            // 연결을 지금 쓸 수 없으면 마지막 CP 상태만 읽는다. 끝나지 않은 job 은 기한 정리가 닫는다.
            return view;
        }
        if (!sameBinding(snapshot, binding)) {
            // 새 연결로 옛 job 을 조회하지 않는다. updatedAt 은 앞으로만 가므로 기다려도 같은 stamp 로 돌아오지 않는다.
            return closeChanged(user, snapshot, job, view);
        }
        var declaration = catalog.find(binding.connectorId()).map(manifest -> manifest.snapshot()).orElse(null);
        if (declaration == null || !Boolean.TRUE.equals(declaration.capabilities().get("status"))) {
            return view;
        }
        var ticketFields = new ContentDraftSaveTicketPayload(UUID.fromString(job.jobId()), snapshot.publicId(), snapshot.snapshotHash(),
            view.actionId(), user.id(), conversationId, snapshot.bindingId(), snapshot.connectionId(),
            snapshot.bindingUpdatedAt(), snapshot.connectionUpdatedAt(), null, null);
        var stamp = ContentDraftWorkerStatusDecoder.stamp(binding.profile(), ticketFields);
        ContentDraftWorkerStatus observed;
        try {
            observed = ContentDraftWorkerStatusDecoder.decode(worker.status(binding.profile(), binding.connectorId(), stamp), stamp, false);
        } catch (ContentDraftJobNotFound missing) {
            // 시작 전송이 아직 worker 에 닿지 않았을 수 있는 동안은 일시 장애와 같게 둔다.
            if (job.status() == ContentDraftJobStatus.DISPATCHED && job.approvedAt().isAfter(clock.instant().minus(START_WINDOW))) {
                throw ContentDraftWorkerStatusDecoder.unavailable();
            }
            observed = null;
        }
        var answer = observed;
        return mutations.run(user.id(), new ChatContentMutationTarget(conversationId, List.of()), () -> {
            access.requireOwn(user, conversationId);
            try {
                bindings.requireUnchanged(user, binding);
            } catch (ApiException ignored) {
                return views(List.of(jobs.findById(job.id()).orElseThrow(ContentDraftJobs::notFound))).getFirst();
            }
            // 승인과 같은 행 순서다. 원격 IO 동안 잡았던 행·TX는 없다.
            var action = actions.findByPublicIdForUpdate(view.actionId()).orElseThrow(ContentDraftJobs::notFound);
            var currentSnapshot = snapshotRows.lockById(snapshot.id()).orElseThrow(ContentDraftJobs::notFound);
            var currentJob = jobs.lockById(job.id()).orElseThrow(ContentDraftJobs::notFound);
            if (!sameBinding(currentSnapshot, binding) || !currentJob.jobId().equals(stamp.jobId().toString())) {
                throw ContentDraftWorkerStatusDecoder.unavailable();
            }
            if (answer != null) {
                currentJob.observed(answer.status(), answer.stage(), answer.saveClicked(), answer.errorCode(), answer.errorReason(),
                    clock.instant());
            } else if (currentJob.awaitingResult()) {
                // worker 는 브라우저를 열기 전에 반드시 start claim 을 받는다. claim 이 없으면 저장 클릭도 없다.
                currentJob.close(ContentDraftJob.NOT_FOUND, clock.instant());
            }
            finishAction(action, currentJob);
            return views(List.of(currentJob)).getFirst();
        });
    }


    /** 연결이나 바인딩 버전이 달라져 결과를 확인하지 못한 job 을 start claim 유무로 닫는다. 승인 줄도 같은 결과로 끝낸다. */
    private ContentDraftJobView closeChanged(CurrentUser user, ContentDraftSnapshot snapshot, ContentDraftJob job, ContentDraftJobView view) {
        return mutations.run(user.id(), new ChatContentMutationTarget(snapshot.conversationId(), List.of()), () -> {
            access.requireOwn(user, snapshot.conversationId());
            var action = actions.findByPublicIdForUpdate(view.actionId()).orElseThrow(ContentDraftJobs::notFound);
            snapshotRows.lockById(snapshot.id()).orElseThrow(ContentDraftJobs::notFound);
            var currentJob = jobs.lockById(job.id()).orElseThrow(ContentDraftJobs::notFound);
            if (currentJob.awaitingResult()) {
                currentJob.close(ContentDraftJob.BINDING_CHANGED, clock.instant());
                finishAction(action, currentJob);
            }
            return views(List.of(currentJob)).getFirst();
        });
    }


    /**
     * 승인한 지 {@code before} 가 지나도록 진행 중인 job 을 한 번 조회하고, 그래도 끝나지 않았으면 닫는다. 아직 EXECUTING 인 승인 줄도
     * 같은 결과로 끝낸다. 원고 승인 줄은 일반 실행 정리 대상이 아니므로 이 정리가 그 줄의 수명을 끝낸다.
     *
     * @param before 이 시각보다 먼저 승인한 job 만 본다. ticket 기한과 worker 실행 상한보다 넉넉히 앞선 시각을 준다
     * @return 닫은 job 수
     */
    public int closeOverdue(Instant before) {
        int closed = 0;
        for (var due : jobs.findByStatusInAndApprovedAtBefore(RUNNING, before)) {
            try {
                if (closeOverdue(due)) {
                    closed++;
                }
            } catch (RuntimeException ex) {
                log.warn("기한이 지난 원고 저장 job 을 닫지 못했다 jobId={} kind={}", due.jobId(), ex.getClass().getSimpleName());
            }
        }
        return closed;
    }


    private boolean closeOverdue(ContentDraftJob due) {
        var snapshot = snapshotRows.findById(due.snapshotId()).orElseThrow(ContentDraftJobs::notFound);
        var owner = owners.findById(due.ownerUserId()).orElse(null);
        if (owner != null) {
            try {
                var user = new CurrentUser(owner.id(), owner.email(), owner.displayName(), owner.groupId(), owner.role());
                refreshJob(user, snapshot, due, views(List.of(due)).getFirst());
            } catch (RuntimeException ignored) {
                // 마지막 조회에 실패해도 닫는다. 결과는 start claim 유무로 정한다.
            }
        }
        var actionId = actions.findById(due.actionId()).orElseThrow(ContentDraftJobs::notFound).publicId();
        var closed = mutations.run(due.ownerUserId(), new ChatContentMutationTarget(snapshot.conversationId(), List.of()), () -> {
            var action = actions.findByPublicIdForUpdate(actionId).orElseThrow(ContentDraftJobs::notFound);
            snapshotRows.lockById(snapshot.id()).orElseThrow(ContentDraftJobs::notFound);
            var job = jobs.lockById(due.id()).orElseThrow(ContentDraftJobs::notFound);
            if (!job.running()) {
                return false;
            }
            job.close(ContentDraftJob.EXPIRED, clock.instant());
            finishAction(action, job);
            return true;
        });
        if (closed) {
            events.publishEvent(new ConnectorActionChanged(snapshot.conversationId(), actionId));
        }
        return closed;
    }


    /** 승인 줄은 EXECUTING 일 때만 job 의 terminal 로 옮긴다. 이미 끝난 줄은 덮지 않는다. */
    private void finishAction(ConnectorAction action, ContentDraftJob job) {
        if (action.status() != ActionStatus.EXECUTING) {
            return;
        }
        switch (job.status()) {
            case SUCCEEDED -> action.succeed(null, null, clock.instant());
            case FAILED -> action.fail("unavailable", null, null, clock.instant());
            case UNKNOWN -> action.unknown(clock.instant());
            default -> {
            }
        }
    }


    private static boolean sameBinding(ContentDraftSnapshot snapshot, ContentDraftBindingStamp binding) {
        return snapshot.connectionId().equals(binding.connectionId()) && snapshot.bindingId().equals(binding.bindingId())
            && snapshot.connectionUpdatedAt().equals(binding.connectionUpdatedAt())
            && snapshot.bindingUpdatedAt().equals(binding.bindingUpdatedAt()) && snapshot.policyFingerprint().equals(binding.policyFingerprint());
    }


    @Override
    @Transactional(readOnly = true)
    public ContentDraftJobPage list(CurrentUser user, Long conversationId, UUID beforeSnapshotId, int limit) {
        if (limit < 1 || limit > MAX_PAGE) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "limit must be between 1 and " + MAX_PAGE);
        }
        access.requireOwn(user, conversationId);
        var page = PageRequest.of(0, limit + 1);
        List<ContentDraftJob> rows;
        if (beforeSnapshotId == null) {
            rows = jobs.findByOwnerUserIdAndConversationIdOrderByIdDesc(user.id(), conversationId, page);
        } else {
            var cursor = snapshotRows.findByPublicIdAndOwnerUserIdAndConversationId(beforeSnapshotId, user.id(), conversationId)
                .flatMap(snapshot -> jobs.findBySnapshotId(snapshot.id())).orElseThrow(ContentDraftJobs::notFound);
            rows = jobs.findByOwnerUserIdAndConversationIdAndIdLessThanOrderByIdDesc(user.id(), conversationId, cursor.id(), page);
        }
        var items = views(rows.subList(0, Math.min(limit, rows.size())));
        return new ContentDraftJobPage(items, rows.size() > limit ? items.getLast().snapshotId() : null);
    }


    /**
     * 승인 카드에 붙일 원고 요약이다. job 링크가 없는 일반 줄은 빠진다. 보관한 상태만 읽고 외부를 부르지 않는다.
     *
     * @param conversationId 주인인 것을 이미 확인한 대화의 번호
     */
    @Transactional(readOnly = true)
    public Map<UUID, ContentDraftActionSummary> summaries(CurrentUser user, Long conversationId, UUID conversationPublicId,
        Collection<UUID> actionIds) {
        if (actionIds.isEmpty()) {
            return Map.of();
        }
        var rowIds = actions.findByConversationIdAndUserIdAndPublicIdIn(conversationId, user.id(), actionIds).stream()
            .map(ConnectorAction::id).toList();
        var rows = jobs.findByActionIdIn(rowIds).stream()
            .filter(job -> job.ownerUserId().equals(user.id()) && job.conversationId().equals(conversationId)).toList();
        return views(rows).stream().filter(view -> view.actionId() != null).collect(Collectors.toMap(ContentDraftJobView::actionId,
            view -> summary(view, conversationPublicId)));
    }


    /**
     * 승인·거절 응답의 그 줄에 붙일 원고 요약이다. 주인의 줄이 아니거나 job 링크가 없으면 빈 값이다. 보관한 상태만 읽는다.
     * 응답에 요약이 없으면 화면이 그 줄을 일반 카드로 바꿔 그린다.
     */
    @Transactional(readOnly = true)
    public Optional<ContentDraftActionSummary> summaryOf(CurrentUser user, UUID actionId) {
        return actions.findByPublicId(actionId).filter(action -> user.id().equals(action.userId()))
            .flatMap(action -> jobs.findByActionId(action.id()))
            .filter(job -> job.ownerUserId().equals(user.id()))
            .flatMap(job -> conversations.findById(job.conversationId())
                .map(conversation -> summary(views(List.of(job)).getFirst(), conversation.publicId())));
    }


    private static ContentDraftActionSummary summary(ContentDraftJobView view, UUID conversationPublicId) {
        return new ContentDraftActionSummary(view.snapshotId(), view.draftId(), view.revision(), conversationPublicId,
            view.status(), view.stage(), view.saveClicked(), view.errorCode(), view.errorReason());
    }


    private ContentDraftSaveRequestView requestView(ContentDraftSnapshot snapshot, ContentDraftJob job) {
        var action = actions.findById(job.actionId()).orElseThrow(ContentDraftJobs::notFound);
        return new ContentDraftSaveRequestView(snapshot.publicId(), action.publicId(), job.jobId() == null ? null : UUID.fromString(job.jobId()),
            status(job, action));
    }


    /** 몇 개의 묶음 조회로 공개 ID 와 원고 판을 붙인다. 본문은 읽지 않는다. */
    private List<ContentDraftJobView> views(List<ContentDraftJob> rows) {
        Map<Long, ContentDraftSnapshot> snapshotById = byId(snapshotRows.findAllById(rows.stream().map(ContentDraftJob::snapshotId).toList()),
            ContentDraftSnapshot::id);
        Map<Long, ConnectorAction> actionById = byId(actions.findAllById(rows.stream().map(ContentDraftJob::actionId).toList()),
            ConnectorAction::id);
        Map<Long, ContentDraftRevision> revisionById = byId(revisions.findAllById(snapshotById.values().stream()
            .map(ContentDraftSnapshot::revisionId).toList()), ContentDraftRevision::id);
        Map<Long, ContentDraft> draftById = byId(drafts.findAllById(revisionById.values().stream().map(ContentDraftRevision::draftId).toList()),
            ContentDraft::id);
        return rows.stream().map(job -> {
            var snapshot = snapshotById.get(job.snapshotId());
            var action = actionById.get(job.actionId());
            var revision = revisionById.get(snapshot.revisionId());
            var draft = revision == null ? null : draftById.get(revision.draftId());
            return new ContentDraftJobView(snapshot.publicId(), draft == null ? null : UUID.fromString(draft.publicId()),
                revision == null ? 0 : revision.revision(), action == null ? null : action.publicId(),
                job.jobId() == null ? null : UUID.fromString(job.jobId()), status(job, action), job.stage(), job.saveClicked(),
                job.lastCheckedAt(), job.errorCode(), job.errorReason());
        }).toList();
    }


    /** 승인 전에 거절·만료로 끝난 줄의 job 은 실행하지 않은 snapshot 이다. */
    static ContentDraftJobStatus status(ContentDraftJob job, ConnectorAction action) {
        if (job.status() == ContentDraftJobStatus.PREVIEW_READY && action != null
            && (action.status() == ActionStatus.REJECTED || action.status() == ActionStatus.EXPIRED)) {
            return ContentDraftJobStatus.STALE;
        }
        return job.status();
    }


    private static <T> Map<Long, T> byId(List<T> rows, Function<T, Long> id) {
        return rows.stream().collect(Collectors.toMap(id, Function.identity()));
    }


    private static ApiException notFound() {
        return new ApiException(ErrorCode.CONVERSATION_NOT_FOUND, "대화를 찾을 수 없습니다.");
    }
}
