package com.bifos.assistant.connector.application;

import com.bifos.assistant.connector.application.model.ConnectorGrantView;
import com.bifos.assistant.connector.domain.ConnectorAction;
import com.bifos.assistant.connector.domain.ConnectorConnection;
import com.bifos.assistant.connector.domain.ConnectorToolGrant;
import com.bifos.assistant.connector.domain.ToolPolicy;
import com.bifos.assistant.connector.domain.type.ActionStatus;
import com.bifos.assistant.connector.infra.ConnectorActionRepository;
import com.bifos.assistant.connector.infra.ConnectorToolGrantRepository;
import com.bifos.assistant.hermes.dto.ConnectorManifest;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionTemplate;

/** 상시 허락과 만료, 중단된 실행, 연결 변경 때의 승인 줄 정리를 맡는다. */
@Slf4j
class ConnectorActionLifecycle {
    private static final String EXECUTING_MESSAGE = "an approved action of this connection is still executing";

    private final ConnectorActionRepository actions;
    private final ConnectorToolGrantRepository grants;
    private final ConnectorActionDetails details;
    private final ConnectorActionSignals signals;
    private final TransactionTemplate transactions;
    private final Clock clock;

    ConnectorActionLifecycle(
            ConnectorActionRepository actions,
            ConnectorToolGrantRepository grants,
            ConnectorActionDetails details,
            ConnectorActionSignals signals,
            TransactionTemplate transactions,
            Clock clock) {
        this.actions = actions;
        this.grants = grants;
        this.details = details;
        this.signals = signals;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * 내 상시 허락 가운데 유효한 것이다.
     *
     * <p>지금 선언이 상시 허락을 닫은 도구의 줄은 내지 않는다(ADR-065). 판정이 그 줄을 보지 않아 효력이 없다.
     * 그런 줄은 {@link #revokeClosedGrants} 가 곧 거둔다. 카탈로그를 읽지 못했으면 낸다.
     */
    List<ConnectorGrantView> grants(CurrentUser user) {
        Map<String, Optional<ConnectorManifest>> manifests = new HashMap<>();
        List<ConnectorGrantView> views = new ArrayList<>();
        for (ConnectorToolGrant grant : grants.findActiveByUserId(user.id(), now())) {
            Optional<ToolPolicy> declared = manifests
                    .computeIfAbsent(grant.connectorId(), details::readManifest)
                    .flatMap(manifest -> ConnectorToolPolicies.find(manifest, grant.toolName()));
            if (declared.isPresent() && !declared.get().grantable()) {
                continue;
            }
            views.add(ConnectorGrantView.from(
                    grant, declared.map(ToolPolicy::title).orElse(null)));
        }
        return views;
    }

    /**
     * 지금 선언이 상시 허락을 닫은 도구의 유효한 허락 줄을 거둔다(ADR-065).
     *
     * <p>판정이 그 줄을 보지 않아 효력은 이미 없다. 그래도 줄이 남으면 사용자는 목록에서 못 보는 줄을 거둘 수 없고,
     * 선언이 상시 허락을 다시 열 때 그 줄이 조용히 되살아난다. 선언이 닫혀 있는 동안 거둬 둔다.
     * 카탈로그를 읽지 못한 커넥터는 닫았는지 알 수 없어 건드리지 않는다. 카탈로그에서 빠졌거나 도구 선언이 없는
     * 줄도 건드리지 않는다. 닫는 선언을 읽은 줄만 거둔다.
     *
     * <p>카탈로그 읽기는 트랜잭션 밖에서 하고 거두는 갱신만 트랜잭션에 넣는다.
     *
     * @return 거둔 건수
     */
    int revokeClosedGrants(Instant now) {
        Map<String, Optional<ConnectorManifest>> manifests = new HashMap<>();
        List<Long> closed = grants.findActive(now).stream()
                .filter(grant -> manifests
                        .computeIfAbsent(grant.connectorId(), details::readManifest)
                        .flatMap(manifest -> ConnectorToolPolicies.find(manifest, grant.toolName()))
                        .filter(declared -> !declared.grantable())
                        .isPresent())
                .map(ConnectorToolGrant::id)
                .toList();
        if (closed.isEmpty()) {
            return 0;
        }
        Integer revoked = transactions.execute(status -> grants.revokeAll(closed, now));
        return revoked == null ? 0 : revoked;
    }

    /** 허락을 거둔다. 남의 허락은 없는 것과 같은 응답이다. 있는지를 알리지 않는다. */
    void revokeGrant(CurrentUser user, Long grantId) {
        ConnectorToolGrant grant = grants.findById(grantId)
                .filter(found -> found.userId().equals(user.id()))
                .orElseThrow(ConnectorActionDetails::notFound);
        grant.revoke(now());
        grants.save(grant);
    }

    /**
     * {@code now} 에 기다리는 시간이 지난 승인 줄을 만료로 바꾼다. 한 줄이 실패해도 나머지를 계속한다.
     *
     * <p>만료와 그 알림은 한 트랜잭션이다. 알림의 도구 제목을 얻으려 카탈로그를 읽는데, 행을 잠근 트랜잭션 안에서
     * 읽지 않으려고 만료할 줄의 커넥터마다 한 번씩 먼저 읽어 둔다. 대화 없는 줄은 알림을 남기지 않으므로 그 커넥터는
     * 읽지 않는다.
     *
     * @return 만료로 바꾼 건수
     */
    int expire(Instant now) {
        List<ConnectorAction> dueActions = actions.findByStatusAndExpiresAtBefore(ActionStatus.PENDING, now);
        Map<String, Optional<ConnectorManifest>> manifests = new HashMap<>();
        dueActions.stream()
                .filter(due -> due.conversationId() != null)
                .forEach(due -> manifests.computeIfAbsent(due.connectorId(), details::readManifest));
        int expired = 0;
        for (ConnectorAction due : dueActions) {
            try {
                // 줄을 잠그고 다시 읽는다. 그 사이 승인이나 거절이 끝났으면 건드리지 않는다.
                Optional<ConnectorAction> changed = transactions.execute(status -> actions.findByPublicIdForUpdate(
                                due.publicId())
                        .filter(action -> action.status() == ActionStatus.PENDING
                                && action.expiresAt().isBefore(now))
                        .map(action -> {
                            action.expire(now);
                            ConnectorAction saved = actions.save(action);
                            signals.notifyExpired(saved, manifests.getOrDefault(saved.connectorId(), Optional.empty()));
                            return saved;
                        }));
                if (changed.isPresent()) {
                    signals.publish(changed.get());
                    expired++;
                }
            } catch (RuntimeException ex) {
                log.warn(
                        "승인 줄을 만료로 바꾸지 못했다 actionId={} kind={}",
                        due.publicId(),
                        ex.getClass().getSimpleName());
            }
        }
        return expired;
    }

    /**
     * 실행을 보낸 채 서버가 내려가 {@code EXECUTING} 으로 남은 줄을 결과를 모르는 것으로 바꾼다. 다시 실행하지 않는다.
     *
     * @return 바꾼 건수
     */
    int markInterrupted(Instant now) {
        List<ConnectorAction> interrupted = transactions.execute(status -> {
            List<ConnectorAction> executing = actions.findByStatus(ActionStatus.EXECUTING);
            executing.forEach(action -> action.unknown(now));
            return actions.saveAll(executing);
        });
        interrupted.forEach(signals::publish);
        return interrupted.size();
    }

    /**
     * 실행을 보낸 지 오래됐는데 {@code EXECUTING} 으로 남은 줄을 결과를 모르는 것으로 바꾼다. 다시 실행하지 않는다.
     *
     * <p>실행은 끝났는데 결과를 적는 저장이 실패하면 줄이 이 상태로 남고, 그 연결의 해제와 다시 등록이 다음 기동까지
     * 막힌다. 결과를 적는 쪽은 {@code EXECUTING} 이 아닌 줄을 건드리지 않으므로 늦게 온 결과와 겹쳐도 안전하다.
     *
     * @param before 이 시각보다 먼저 승인한 줄만 바꾼다. 실행의 시간 제한보다 넉넉히 앞선 시각을 준다
     * @return 바꾼 건수
     */
    int markStale(Instant before, Instant now) {
        int changed = 0;
        for (ConnectorAction stale : actions.findByStatusAndDecidedAtBefore(ActionStatus.EXECUTING, before)) {
            try {
                Optional<ConnectorAction> unknown =
                        transactions.execute(status -> actions.findByPublicIdForUpdate(stale.publicId())
                                .filter(action -> action.status() == ActionStatus.EXECUTING)
                                .map(action -> {
                                    action.unknown(now);
                                    return actions.save(action);
                                }));
                if (unknown.isPresent()) {
                    signals.publish(unknown.get());
                    changed++;
                }
            } catch (RuntimeException ex) {
                log.warn(
                        "오래 남은 실행 줄을 정리하지 못했다 actionId={} kind={}",
                        stale.publicId(),
                        ex.getClass().getSimpleName());
            }
        }
        return changed;
    }

    /**
     * 그 연결의 답을 기다리는 줄을 모두 거절하고 유효한 상시 허락을 거둔다. 연결을 해제하거나 값을 다시 등록하는
     * 트랜잭션 안에서 부른다.
     *
     * <p>다른 계정으로 바꾼 뒤 앞선 계정에 한 승인이 실행되지 않게 한다. 사건은 그 트랜잭션이 커밋한 뒤에 낸다.
     *
     * <p>승인해 실행을 보낸 줄이 있으면 {@code CONNECTOR_ACTION_EXECUTING} 으로 거절한다. 실행은 트랜잭션 밖에서
     * 돌고 그때의 계정 값을 읽으므로, 그 사이 값을 바꾸면 앞선 계정에 한 승인이 새 계정으로 실행된다. 부르는 쪽이
     * 사용자 행을 잠근 채 부르고 승인도 같은 행을 먼저 잠그므로, 여기서 본 뒤에 새로 {@code EXECUTING} 이 되는 줄은
     * 없다. 외부에 무엇을 반영하기 전에 부른다.
     */
    void rejectPendingFor(ConnectorConnection connection, Instant now) {
        if (actions.existsByUserIdAndConnectorIdAndStatus(
                connection.userId(), connection.connectorId(), ActionStatus.EXECUTING)) {
            throw new ApiException(ErrorCode.CONNECTOR_ACTION_EXECUTING, EXECUTING_MESSAGE);
        }
        List<ConnectorAction> pending = actions.findByUserIdAndConnectorIdAndStatusForUpdate(
                connection.userId(), connection.connectorId(), ActionStatus.PENDING);
        pending.forEach(action -> action.refuse(ConnectorAction.CONNECTION_CHANGED, now));
        List<ConnectorAction> rejected = actions.saveAll(pending);
        List<ConnectorToolGrant> active =
                grants.findActiveByUserIdAndConnectorId(connection.userId(), connection.connectorId(), now);
        active.forEach(grant -> grant.revoke(now));
        grants.saveAll(active);
        signals.publishAfterCommit(rejected);
    }

    /**
     * 그 에이전트에서 연결을 뗄 때 그 에이전트의 실행이 판정한 줄만 거절한다. 트랜잭션 안에서 부른다.
     *
     * <p>상시 허락은 건드리지 않는다. 허락은 사용자와 커넥터에 묶여 같은 연결을 붙인 다른 에이전트에도 걸린다.
     * {@code EXECUTING} 이 남은 줄이 있으면 {@code CONNECTOR_ACTION_EXECUTING} 으로 거절한다. 실행이 그 profile 의 값으로
     * 돌고 있어 그 사이 떼면 결과를 알 수 없다. 부르는 쪽이 사용자 행을 잠근 채 부르고 승인도 같은 행을 먼저 잠그므로
     * 줄을 따로 잠그지 않는다.
     */
    void rejectPendingFor(ConnectorConnection connection, Long agentId, Instant now) {
        if (!actions.findByUserIdAndConnectorIdAndAgentIdAndStatus(
                        connection.userId(), connection.connectorId(), agentId, ActionStatus.EXECUTING)
                .isEmpty()) {
            throw new ApiException(ErrorCode.CONNECTOR_ACTION_EXECUTING, EXECUTING_MESSAGE);
        }
        List<ConnectorAction> pending = actions.findByUserIdAndConnectorIdAndAgentIdAndStatus(
                connection.userId(), connection.connectorId(), agentId, ActionStatus.PENDING);
        pending.forEach(action -> action.refuse(ConnectorAction.CONNECTION_CHANGED, now));
        signals.publishAfterCommit(actions.saveAll(pending));
    }

    private Instant now() {
        return Instant.now(clock);
    }
}
