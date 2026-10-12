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
import com.bifos.assistant.connector.application.model.ConnectorActionChanged;
import com.bifos.assistant.connector.domain.ConnectorAction;
import com.bifos.assistant.connector.domain.type.ActionStatus;
import com.bifos.assistant.connector.infra.ConnectorActionRepository;
import com.bifos.assistant.connector.infra.ConnectorBindingRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.config.LiveProperties;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.shared.util.Sha256;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 원고 snapshot 의 저장 요청과 보관한 job 조회를 맡는다. chat 의 두 port 를 구현한다.
 *
 * <p>저장 요청은 기존 승인 줄 하나와 job 링크 하나를 같은 트랜잭션에서 만든다. 같은 snapshot 의 두 번째 요청은 앞의 줄을 그대로
 * 돌려준다. 조회는 Control Plane 이 이미 보관한 상태만 읽고 외부를 부르지 않는다.
 */
@Service
@RequiredArgsConstructor
public class ContentDraftJobs implements ContentDraftSaveRequests, ContentDraftJobQueries {
    static final int MAX_PAGE = 20;
    private static final String DEDUPE_PREFIX = "v1-content-save";
    /** MCP 서버의 등록 이름이 아니므로 일반 승인 경로는 이 줄을 선언 없는 도구로 보고 실행 없이 거절한다. */
    static final String HERMES_TOOL = "fos_content_draft_save";

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
    private final ConnectorBindingRepository bindingRows;
    private final ConnectorCatalogCache catalog;
    private final AgentExecutionRepository executions;
    private final LiveProperties<ConnectorPolicyProperties> policy;
    private final ApplicationEventPublisher events;
    private final Clock clock;


    @Override
    public ContentDraftSaveRequestView requestSave(CurrentUser user, Long originExecutionId, UUID snapshotId) {
        var snapshot = snapshotRows.findByPublicIdAndOwnerUserId(snapshotId, user.id()).orElseThrow(ContentDraftJobs::notFound);
        Long conversationId = snapshot.conversationId();
        // 지금 호출한 실행이 같은 사용자·대화의 취소되지 않은 실행이고 snapshot 의 에이전트와 같아야 한다.
        if (!snapshot.agentId().equals(inputs.agent(user, conversationId, originExecutionId))) {
            throw ContentDraftSnapshotHash.unavailable();
        }
        var existing = jobs.findBySnapshotId(snapshot.id());
        if (existing.isPresent()) {
            return requestView(snapshot, existing.get());
        }
        var stamp = bindings.prepare(user, snapshot.agentId(), snapshot.bindingId());
        var manifest = catalog.find(stamp.connectorId()).orElseThrow(ContentDraftSnapshotHash::unavailable);
        String argsJson = "{\"snapshotId\":\"" + snapshotId + "\"}";
        var decision = ContentDraftSaveApproval.decide(manifest, argsJson).orElseThrow(ContentDraftSnapshotHash::unavailable);
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
                var origin = executions.findById(originExecutionId).orElseThrow(ContentDraftSnapshotHash::unavailable);
                var connection = bindingRows.findById(snapshot.bindingId()).orElseThrow(ContentDraftSnapshotHash::unavailable).connection();
                var action = ConnectorAction.decided(connection, snapshot.agentId(), origin,
                    HERMES_TOOL, ContentDraftSaveApproval.SAVE_TOOL,
                    decision, false, Sha256.hex(DEDUPE_PREFIX + ":" + user.id() + ":" + snapshotId), Sha256.hex(argsJson), now);
                action.awaitApproval(argsJson, now.plus(policy.current().approvalTtl()));
                var saved = actions.saveAndFlush(action);
                var job = jobs.saveAndFlush(new ContentDraftJob(snapshot.id(), saved.id(), user.id(), conversationId, now));
                return requestView(snapshot, job);
            });
            // 사건은 커밋한 뒤에 낸다. 받은 쪽이 읽을 때 승인 줄이 있어야 한다.
            events.publishEvent(new ConnectorActionChanged(conversationId, created.actionId()));
            return created;
        } catch (DataIntegrityViolationException ex) {
            return jobs.findBySnapshotId(snapshot.id()).map(job -> requestView(snapshot, job)).orElseThrow(() -> ex);
        }
    }


    @Override
    @Transactional(readOnly = true)
    public ContentDraftJobView refresh(CurrentUser user, Long conversationId, UUID draftId, UUID snapshotId) {
        access.requireOwn(user, conversationId);
        var snapshot = snapshotRows.findByPublicIdAndOwnerUserIdAndConversationId(snapshotId, user.id(), conversationId)
            .orElseThrow(ContentDraftJobs::notFound);
        var job = jobs.findBySnapshotId(snapshot.id()).orElseThrow(ContentDraftJobs::notFound);
        var view = views(List.of(job)).getFirst();
        if (!draftId.equals(view.draftId())) {
            throw notFound();
        }
        return view;
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
                job.lastCheckedAt(), job.errorCode());
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
