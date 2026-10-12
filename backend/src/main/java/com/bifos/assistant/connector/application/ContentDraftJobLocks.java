package com.bifos.assistant.connector.application;

import com.bifos.assistant.chat.infra.ContentDraftJobRepository;
import com.bifos.assistant.chat.infra.ContentDraftSnapshotRepository;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.connector.infra.ConnectorActionRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.UserAccessPolicy;
import com.bifos.assistant.usage.domain.type.ExecutionStatus;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 원고 저장의 승인과 worker 허가가 함께 쓰는 잠금 순서다.
 *
 * <p>coordinator 가 사용자 → 대화 → 첨부를 잠근 뒤, 이 함수가 허용 목록 행 → 에이전트 → origin·루트 실행 오름차순 → 승인 줄 →
 * snapshot → job 을 잠근다. 사용자 끄기·에이전트 비활성·실행 취소 쓰기와 같은 행을 기다리므로 먼저 커밋된 철회를 놓치지 않는다.
 */
@Component
@RequiredArgsConstructor
class ContentDraftJobLocks {
    private final UserAccessPolicy access;
    private final AgentRepository agents;
    private final AgentExecutionRepository executions;
    private final ConnectorActionRepository actions;
    private final ContentDraftSnapshotRepository snapshots;
    private final ContentDraftJobRepository jobs;


    /** 트랜잭션 밖에서 잠글 실행 번호를 모은다. 루트 번호는 바뀌지 않는 값이다. */
    List<Long> executionIds(Long... originIds) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("execution ids must be read before the content lock");
        }
        return Stream.of(originIds)
            .flatMap(id -> Stream.of(id, executions.findById(id).orElseThrow(() -> new ContentDraftRefusal("origin")).treeRootId()))
            .distinct().sorted().toList();
    }


    /** coordinator 콜백 안에서만 부른다. 맞지 않으면 {@link ContentDraftRefusal} 로 끝낸다. */
    ContentDraftLockedJob lock(CurrentUser user, Long conversationId, Long agentId, List<Long> executionIds, UUID actionId,
        Long snapshotRowId, Long jobRowId) {
        require(access.allowedForUpdate(user.id()), "user");
        var agent = agents.findByIdForUpdate(agentId).orElseThrow(() -> new ContentDraftRefusal("agent"));
        require(agent.enabled() && agent.isReadableBy(user.id()), "agent");
        for (Long id : executionIds) {
            var execution = executions.lockById(id).orElseThrow(() -> new ContentDraftRefusal("origin"));
            require(user.id().equals(execution.userId()) && conversationId.equals(execution.conversationId())
                && execution.status() != ExecutionStatus.CANCELLED, "origin");
        }
        var action = actions.findByPublicIdForUpdate(actionId).filter(found -> found.userId().equals(user.id()))
            .orElseThrow(ConnectorActionDetails::notFound);
        var snapshot = snapshots.lockById(snapshotRowId).orElseThrow(ConnectorActionDetails::notFound);
        var job = jobs.lockById(jobRowId).orElseThrow(ConnectorActionDetails::notFound);
        if (!job.actionId().equals(action.id()) || !job.snapshotId().equals(snapshot.id())
            || !user.id().equals(snapshot.ownerUserId()) || !conversationId.equals(snapshot.conversationId())
            || !agentId.equals(snapshot.agentId())) {
            throw ConnectorActionDetails.notFound();
        }
        return new ContentDraftLockedJob(action, snapshot, job);
    }


    private static void require(boolean condition, String code) {
        if (!condition) {
            throw new ContentDraftRefusal(code);
        }
    }
}
