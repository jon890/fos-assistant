package com.bifos.assistant.connector.application;

import com.bifos.assistant.connector.application.model.ConnectorActionChanged;
import com.bifos.assistant.connector.application.model.ConnectorActionView;
import com.bifos.assistant.connector.application.model.ConnectorGrantView;
import com.bifos.assistant.connector.domain.ConnectorAction;
import com.bifos.assistant.connector.domain.ConnectorConnection;
import com.bifos.assistant.connector.domain.ConnectorToolGrant;
import com.bifos.assistant.connector.domain.ToolPolicy;
import com.bifos.assistant.connector.domain.ToolPolicyDecision;
import com.bifos.assistant.connector.domain.type.ActionDecision;
import com.bifos.assistant.connector.domain.type.ActionStatus;
import com.bifos.assistant.connector.domain.type.ConnectionStatus;
import com.bifos.assistant.connector.domain.type.GrantPeriod;
import com.bifos.assistant.connector.domain.type.ToolApproval;
import com.bifos.assistant.connector.infra.ConnectorActionRepository;
import com.bifos.assistant.connector.infra.ConnectorConnectionRepository;
import com.bifos.assistant.connector.infra.ConnectorToolGrantRepository;
import com.bifos.assistant.hermes.ConnectorExecutionUnknown;
import com.bifos.assistant.hermes.HermesConnectorClient;
import com.bifos.assistant.hermes.dto.CallResult;
import com.bifos.assistant.hermes.dto.ConnectorManifest;
import com.bifos.assistant.orchestration.application.DelegationProperties;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 승인 줄의 승인과 거절, 승인한 호출의 실행, 상시 허락, 만료를 맡는다(ADR-050).
 *
 * <p>계약은 {@code docs/connectors.md} 의 「승인」 이 갖는다. 줄의 주인만 다룬다. 남의 줄은 관리자에게도 없는 줄과 같은
 * 응답이다. 인자 원문과 실행 결과는 로그에 싣지 않는다.
 *
 * <p>한 번만 실행하는 것은 두 가지로 지킨다. {@code PENDING} 에서 {@code EXECUTING} 으로 가는 전이를 행 잠금 아래에서
 * 하고, 그 전이를 커밋한 뒤에만 실행을 보낸다. 실행을 보낸 뒤 서버가 죽어도 그 줄은 {@code PENDING} 으로 돌아가지
 * 않는다. 사건은 줄을 커밋한 뒤에 낸다.
 */
@Slf4j
@Service
public class ConnectorActionService {
    private static final String NOT_FOUND_MESSAGE = "this connector action does not exist";

    private static final String NOT_PENDING_MESSAGE = "this connector action is not waiting for approval";

    private static final String EXECUTING_MESSAGE = "an approved action of this connection is still executing";

    /**
     * 「오늘」 의 끝을 정하는 시간대다. 가족이 사는 곳의 날짜로 끝나야 하므로 서버 시간대에 기대지 않는다.
     *
     * <p>{@code UsageController} 의 달 경계와 같은 값이다. 그룹마다 시간대를 두게 되면 둘을 함께 고친다.
     */
    private static final ZoneId HOUSEHOLD_ZONE = ZoneId.of("Asia/Seoul");

    private final ConnectorActionRepository actions;
    private final ConnectorToolGrantRepository grants;
    private final ConnectorConnectionRepository connections;
    private final AppUserRepository users;
    private final ConnectorCatalogCache catalog;
    private final HermesConnectorClient connector;
    private final DelegationProperties delegation;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transactions;
    private final Clock clock;

    // 생성자를 직접 쓴다. TransactionTemplate 은 transaction manager 로 여기서 만들고,
    // 검사가 시각을 고정할 수 있게 Clock 을 받는 생성자를 따로 둔다.
    @Autowired
    public ConnectorActionService(
            ConnectorActionRepository actions,
            ConnectorToolGrantRepository grants,
            ConnectorConnectionRepository connections,
            AppUserRepository users,
            ConnectorCatalogCache catalog,
            HermesConnectorClient connector,
            DelegationProperties delegation,
            ApplicationEventPublisher events,
            PlatformTransactionManager transactionManager) {
        this(
                actions,
                grants,
                connections,
                users,
                catalog,
                connector,
                delegation,
                events,
                transactionManager,
                Clock.systemUTC());
    }

    public ConnectorActionService(
            ConnectorActionRepository actions,
            ConnectorToolGrantRepository grants,
            ConnectorConnectionRepository connections,
            AppUserRepository users,
            ConnectorCatalogCache catalog,
            HermesConnectorClient connector,
            DelegationProperties delegation,
            ApplicationEventPublisher events,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.actions = actions;
        this.grants = grants;
        this.connections = connections;
        this.users = users;
        this.catalog = catalog;
        this.connector = connector;
        this.delegation = delegation;
        this.events = events;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * 그 대화의 승인 줄이다. 답을 기다리는 줄 전부와 끝난 줄 가운데 최근 20개를 만든 순으로 준다.
     *
     * @param conversationId 주인인 것을 이미 확인한 대화의 번호
     */
    @Transactional(readOnly = true)
    public List<ConnectorActionView> listForConversation(CurrentUser user, Long conversationId) {
        List<ConnectorAction> found = new ArrayList<>(actions.findByConversationIdAndUserIdAndStatusOrderByIdAsc(
                conversationId, user.id(), ActionStatus.PENDING));
        found.addAll(actions.findTop20ByConversationIdAndUserIdAndStatusNotOrderByIdDesc(
                conversationId, user.id(), ActionStatus.PENDING));
        found.sort(Comparator.comparing(ConnectorAction::id));
        Map<String, Optional<ConnectorManifest>> manifests = new HashMap<>();
        return found.stream()
                .map(action -> ConnectorActionView.from(
                        action,
                        manifests
                                .computeIfAbsent(action.connectorId(), this::readManifest)
                                .flatMap(manifest -> ConnectorToolPolicies.find(manifest, action.toolName()))
                                .map(ToolPolicy::title)
                                .orElse(null)))
                .toList();
    }

    /**
     * 승인하고 저장한 인자로 한 번 실행한다.
     *
     * <p>실행 호출은 트랜잭션 밖에서 한다. 최대 한 분 넘게 걸려 그동안 DB 연결을 쥐지 않는다. 이 메서드에
     * {@code @Transactional} 을 붙이지 않는다. 붙이면 실행 호출이 트랜잭션 안으로 들어가고, {@code EXECUTING} 이
     * 커밋되기 전에 실행이 나간다.
     *
     * <p>요청이 왔을 때 이미 {@code PENDING} 이 아니던 줄은 {@code CONNECTOR_ACTION_NOT_PENDING} 이다. 승인은
     * 받았으나 실행할 수 없어 여기서 끝낸 줄(기다리는 시간이 지남, 연결이 준비되지 않음, 정책이 바뀜)은 오류가 아니라
     * 끝난 줄을 그대로 돌려준다. 부른 쪽이 「이미 처리됐다」 와 「승인했지만 실행하지 않았다」 를 구분한다.
     *
     * @param grant 승인하면서 그 도구에 줄 상시 허락의 기간. 주지 않으면 null
     */
    public ConnectorActionView approve(CurrentUser user, UUID actionId, GrantPeriod grant) {
        Approval approval = transactions.execute(status -> beginApproval(user, actionId, grant));
        if (approval.profile() == null) {
            // 실행하지 않고 끝낸 줄이다. 그 상태를 커밋한 뒤에 알린다.
            publish(approval.action());
            return view(approval.action());
        }
        ConnectorAction waiting = approval.action();
        CallResult result = null;
        try {
            result = connector.execute(
                    approval.profile(), waiting.connectorId(), waiting.hermesTool(), waiting.argsJson());
        } catch (ConnectorExecutionUnknown ex) {
            log.warn("승인한 호출의 실행 결과를 알 수 없다 actionId={}", actionId);
        } catch (RuntimeException ex) {
            // 실행됐는지 알 수 없는 것으로 둔다. 예외 메시지에는 원격 응답이 섞일 수 있어 종류만 남긴다.
            log.warn(
                    "승인한 호출의 실행이 예외로 끝났다 actionId={} kind={}",
                    actionId,
                    ex.getClass().getSimpleName());
        }
        CallResult executed = result;
        ConnectorAction finished = transactions.execute(status -> record(actionId, executed));
        publish(finished);
        return view(finished);
    }

    /** 승인 줄을 거절한다. 실행하지 않는다. */
    public ConnectorActionView reject(CurrentUser user, UUID actionId) {
        ConnectorAction rejected = transactions.execute(status -> {
            ConnectorAction action = requirePending(user, actionId);
            action.reject(now());
            return actions.save(action);
        });
        publish(rejected);
        return view(rejected);
    }

    /** 내 상시 허락 가운데 유효한 것이다. */
    @Transactional(readOnly = true)
    public List<ConnectorGrantView> grants(CurrentUser user) {
        return grants.findActiveByUserId(user.id(), now()).stream()
                .map(ConnectorGrantView::from)
                .toList();
    }

    /** 허락을 거둔다. 남의 허락은 없는 것과 같은 응답이다. 있는지를 알리지 않는다. */
    @Transactional
    public void revokeGrant(CurrentUser user, Long grantId) {
        ConnectorToolGrant grant = grants.findById(grantId)
                .filter(found -> found.userId().equals(user.id()))
                .orElseThrow(ConnectorActionService::notFound);
        grant.revoke(now());
        grants.save(grant);
    }

    /**
     * {@code now} 에 기다리는 시간이 지난 승인 줄을 만료로 바꾼다. 한 줄이 실패해도 나머지를 계속한다.
     *
     * @return 만료로 바꾼 건수
     */
    public int expire(Instant now) {
        int expired = 0;
        for (ConnectorAction due : actions.findByStatusAndExpiresAtBefore(ActionStatus.PENDING, now)) {
            try {
                // 줄을 잠그고 다시 읽는다. 그 사이 승인이나 거절이 끝났으면 건드리지 않는다.
                Optional<ConnectorAction> changed =
                        transactions.execute(status -> actions.findByPublicIdForUpdate(due.publicId())
                                .filter(action -> action.status() == ActionStatus.PENDING
                                        && action.expiresAt().isBefore(now))
                                .map(action -> {
                                    action.expire(now);
                                    return actions.save(action);
                                }));
                if (changed.isPresent()) {
                    publish(changed.get());
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
    public int markInterrupted(Instant now) {
        List<ConnectorAction> interrupted = transactions.execute(status -> {
            List<ConnectorAction> executing = actions.findByStatus(ActionStatus.EXECUTING);
            executing.forEach(action -> action.unknown(now));
            return actions.saveAll(executing);
        });
        interrupted.forEach(this::publish);
        return interrupted.size();
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
    public void rejectPendingFor(ConnectorConnection connection, Instant now) {
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
        if (rejected.isEmpty()) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            rejected.forEach(this::publish);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                rejected.forEach(ConnectorActionService.this::publish);
            }
        });
    }

    /**
     * 줄을 잠그고 승인할 수 있는지 본 뒤 {@code EXECUTING} 으로 바꾼다. 트랜잭션 안에서만 부른다.
     *
     * <p>기다리는 시간이 지났거나, 연결이 준비되지 않았거나, 지금 정책으로는 실행할 수 없는 줄은 실행하지 않고
     * 끝낸다. 그 상태를 커밋해야 하므로 예외로 알리지 않고 profile 없는 결과로 돌려준다. 정책은 요청을 만들 때가
     * 아니라 지금 것으로 다시 본다. 그 사이 운영자가 도구를 선언에서 뺐거나 위험도를 올렸을 수 있다.
     */
    private Approval beginApproval(CurrentUser user, UUID actionId, GrantPeriod grant) {
        Instant now = now();
        // 연결을 다시 등록하거나 해제하는 쪽과 같은 순서로 잠근다. 사용자 행이 먼저이고 승인 줄이 다음이다.
        // 그쪽이 값을 바꾸는 동안에는 여기서 기다리고, 그쪽이 커밋한 뒤에는 이 줄이 이미 PENDING 이 아니다.
        users.findByIdForUpdate(user.id()).orElseThrow(ConnectorActionService::notFound);
        ConnectorAction action = requirePending(user, actionId);
        if (!action.expiresAt().isAfter(now)) {
            action.expire(now);
            return Approval.refused(actions.save(action));
        }
        if (grant != null && !action.grantAllowed()) {
            throw grantNotAllowed();
        }
        Optional<ConnectorConnection> connection =
                connections.findByUserIdAndConnectorId(action.userId(), action.connectorId());
        Optional<ToolPolicyDecision> decision = connection
                .filter(found -> found.status() == ConnectionStatus.READY)
                .flatMap(found -> readManifest(found.connectorId()).map(manifest -> redecide(found, manifest, action)));
        if (decision.isEmpty() || decision.get().decision() == ActionDecision.DENIED) {
            action.refuse(ConnectorAction.NOT_EXECUTABLE, now);
            return Approval.refused(actions.save(action));
        }
        if (grant != null) {
            // 요청을 만든 뒤 늘 승인을 받는 도구로 바뀌었으면 허락을 주지 않는다.
            if (decision.get().approval() != ToolApproval.REQUIRED) {
                throw grantNotAllowed();
            }
            grants.save(ConnectorToolGrant.of(
                    action.userId(),
                    action.connectorId(),
                    action.toolName(),
                    grant.expiresAt(now, HOUSEHOLD_ZONE),
                    now));
        }
        action.beginExecution(now);
        return new Approval(actions.save(action), connection.get().agent().hermesProfile());
    }

    /** 상시 허락은 없는 것으로 두고 지금 정책으로 다시 판정한다. 허락이 있어 통과하는 호출은 승인 줄이 되지 않는다. */
    private static ToolPolicyDecision redecide(
            ConnectorConnection connection, ConnectorManifest manifest, ConnectorAction action) {
        return ToolPolicyDecision.decide(
                connection.status(),
                ConnectorPolicyService.ownServerTool(manifest, action.hermesTool()),
                manifest.schema(),
                ConnectorToolPolicies.find(manifest, action.toolName()),
                false,
                action.argsJson().getBytes(StandardCharsets.UTF_8).length);
    }

    /**
     * 실행 결과를 줄에 적는다. 트랜잭션 안에서만 부른다.
     *
     * @param result 실행 결과. 실행됐는지 알 수 없으면 null
     */
    private ConnectorAction record(UUID actionId, CallResult result) {
        Instant now = now();
        ConnectorAction action =
                actions.findByPublicIdForUpdate(actionId).orElseThrow(ConnectorActionService::notFound);
        if (action.status() != ActionStatus.EXECUTING) {
            // 실행하는 동안 기동 정리가 이 줄을 이미 끝냈다. 그 상태를 그대로 둔다.
            return action;
        }
        if (result == null) {
            action.unknown(now);
        } else if (result.ok()) {
            action.succeed(clip(result.result().toString()), now);
        } else {
            action.fail(result.error().word(), null, now);
        }
        return actions.save(action);
    }

    /** 위임 답과 같은 상한으로 자른다. 상한 자리에서 대리 쌍이 나뉘면 그 앞에서 자른다. */
    private String clip(String text) {
        int max = delegation.outputMaxChars();
        if (text.length() <= max) {
            return text;
        }
        return text.substring(0, Character.isHighSurrogate(text.charAt(max - 1)) ? max - 1 : max);
    }

    /** 줄을 잠그고 읽는다. 없는 줄과 남의 줄은 같은 응답이다. 트랜잭션 안에서만 부른다. */
    private ConnectorAction requirePending(CurrentUser user, UUID actionId) {
        ConnectorAction action = actions.findByPublicIdForUpdate(actionId)
                .filter(found -> found.userId().equals(user.id()))
                .orElseThrow(ConnectorActionService::notFound);
        if (action.status() != ActionStatus.PENDING) {
            throw notPending();
        }
        return action;
    }

    /** 카탈로그에서 그 도구의 이름을 찾아 붙인다. 카탈로그를 읽지 못해도 줄은 돌려준다. */
    private ConnectorActionView view(ConnectorAction action) {
        return ConnectorActionView.from(
                action,
                readManifest(action.connectorId())
                        .flatMap(manifest -> ConnectorToolPolicies.find(manifest, action.toolName()))
                        .map(ToolPolicy::title)
                        .orElse(null));
    }

    /** 읽지 못했거나 카탈로그에 없으면 빈 값이다. 예외 메시지에는 원격 응답이 섞일 수 있어 종류만 남긴다. */
    private Optional<ConnectorManifest> readManifest(String connectorId) {
        try {
            return catalog.find(connectorId);
        } catch (RuntimeException ex) {
            log.warn(
                    "connector {} catalog read failed for an approval: {}",
                    connectorId,
                    ex.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /**
     * 대화 없는 실행이 만든 줄은 전할 곳이 없어 내지 않는다. 줄을 커밋한 뒤에만 부른다.
     *
     * <p>듣는 쪽의 예외를 밖으로 내지 않는다. 줄은 이미 커밋됐고, 예외가 나가면 실행한 승인이 실패한 것처럼 보인다.
     */
    private void publish(ConnectorAction action) {
        if (action.conversationId() == null) {
            return;
        }
        try {
            events.publishEvent(new ConnectorActionChanged(action.conversationId(), action.publicId()));
        } catch (RuntimeException ex) {
            log.warn("승인 줄이 바뀐 것을 알리지 못했다 actionId={}", action.publicId(), ex);
        }
    }

    private Instant now() {
        return Instant.now(clock);
    }

    private static ApiException notFound() {
        return new ApiException(ErrorCode.CONNECTOR_ACTION_NOT_FOUND, NOT_FOUND_MESSAGE);
    }

    private static ApiException notPending() {
        return new ApiException(ErrorCode.CONNECTOR_ACTION_NOT_PENDING, NOT_PENDING_MESSAGE);
    }

    private static ApiException grantNotAllowed() {
        return new ApiException(ErrorCode.VALIDATION_FAILED, "this tool cannot be granted standing approval");
    }

    /**
     * 승인의 첫 트랜잭션이 낸 결과다.
     *
     * @param profile 실행을 보낼 profile. 실행하지 않고 끝낸 줄이면 null
     */
    private record Approval(ConnectorAction action, String profile) {
        private static Approval refused(ConnectorAction action) {
            return new Approval(action, null);
        }
    }
}
