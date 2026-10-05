package com.bifos.assistant.connector.application;

import com.bifos.assistant.chat.application.ConversationNotices;
import com.bifos.assistant.connector.application.model.ConnectorActionChanged;
import com.bifos.assistant.connector.application.model.ConnectorActionResult;
import com.bifos.assistant.connector.application.model.ConnectorActionView;
import com.bifos.assistant.connector.application.model.ConnectorGrantView;
import com.bifos.assistant.connector.application.model.PendingApproval;
import com.bifos.assistant.connector.domain.ActionDelivery;
import com.bifos.assistant.connector.domain.ConnectorAction;
import com.bifos.assistant.connector.domain.ConnectorBinding;
import com.bifos.assistant.connector.domain.ConnectorConnection;
import com.bifos.assistant.connector.domain.ConnectorToolGrant;
import com.bifos.assistant.connector.domain.ToolPolicy;
import com.bifos.assistant.connector.domain.ToolPolicyDecision;
import com.bifos.assistant.connector.domain.ToolPolicyDecision.CheckBoundary;
import com.bifos.assistant.connector.domain.type.ActionDecision;
import com.bifos.assistant.connector.domain.type.ActionStatus;
import com.bifos.assistant.connector.domain.type.GrantPeriod;
import com.bifos.assistant.connector.domain.type.ToolApproval;
import com.bifos.assistant.connector.infra.ConnectorActionRepository;
import com.bifos.assistant.connector.infra.ConnectorBindingRepository;
import com.bifos.assistant.connector.infra.ConnectorConnectionRepository;
import com.bifos.assistant.connector.infra.ConnectorToolGrantRepository;
import com.bifos.assistant.hermes.ConnectorExecutionUnknown;
import com.bifos.assistant.hermes.HermesConnectorClient;
import com.bifos.assistant.hermes.ToolDetailRedactor;
import com.bifos.assistant.hermes.dto.CallResult;
import com.bifos.assistant.hermes.dto.ConnectorManifest;
import com.bifos.assistant.notification.application.NotificationService;
import com.bifos.assistant.notification.domain.NotificationTarget;
import com.bifos.assistant.notification.domain.type.NotificationKind;
import com.bifos.assistant.notification.domain.type.NotificationTargetType;
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
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
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

    /** 실행을 보낸 뒤 끝난 상태들이다. 자동 turn 으로 모델에게 전한다. */
    private static final Set<ActionStatus> EXECUTED =
            Set.of(ActionStatus.SUCCEEDED, ActionStatus.FAILED, ActionStatus.UNKNOWN);

    /** 실행하지 않고 끝난 상태들이다. 대화에 알림 줄만 남긴다. */
    private static final Set<ActionStatus> CLOSED_WITHOUT_EXECUTION =
            Set.of(ActionStatus.REJECTED, ActionStatus.EXPIRED);

    /** 만료한 승인 줄을 알리는 알림의 제목이다. 본문은 도구 제목이다. */
    static final String APPROVAL_EXPIRED_TITLE = "승인 요청이 만료됐어요";

    private static final String EXECUTING_MESSAGE = "an approved action of this connection is still executing";

    /** 도구를 선언하지 않는 manifest 판이다. */
    private static final int SCHEMA_WITHOUT_TOOLS = 1;

    /**
     * 「오늘」 의 끝을 정하는 시간대다. 가족이 사는 곳의 날짜로 끝나야 하므로 서버 시간대에 기대지 않는다.
     *
     * <p>{@code UsageController} 의 달 경계와 같은 값이다. 그룹마다 시간대를 두게 되면 둘을 함께 고친다.
     */
    private static final ZoneId HOUSEHOLD_ZONE = ZoneId.of("Asia/Seoul");

    private final ConnectorActionRepository actions;
    private final ConnectorToolGrantRepository grants;
    private final ConnectorConnectionRepository connections;
    private final ConnectorBindingRepository bindings;
    private final AppUserRepository users;
    private final ConnectorCatalogCache catalog;
    private final HermesConnectorClient connector;
    private final DelegationProperties delegation;
    private final ApplicationEventPublisher events;
    private final NotificationService notifications;
    private final ConversationNotices conversations;
    private final TransactionTemplate transactions;
    private final Clock clock;

    // 생성자를 직접 쓴다. TransactionTemplate 은 transaction manager 로 여기서 만들고,
    // 검사가 시각을 고정할 수 있게 Clock 을 받는 생성자를 따로 둔다.
    @Autowired
    public ConnectorActionService(
            ConnectorActionRepository actions,
            ConnectorToolGrantRepository grants,
            ConnectorConnectionRepository connections,
            ConnectorBindingRepository bindings,
            AppUserRepository users,
            ConnectorCatalogCache catalog,
            HermesConnectorClient connector,
            DelegationProperties delegation,
            ApplicationEventPublisher events,
            NotificationService notifications,
            ConversationNotices conversations,
            PlatformTransactionManager transactionManager) {
        this(
                actions,
                grants,
                connections,
                bindings,
                users,
                catalog,
                connector,
                delegation,
                events,
                notifications,
                conversations,
                transactionManager,
                Clock.systemUTC());
    }

    public ConnectorActionService(
            ConnectorActionRepository actions,
            ConnectorToolGrantRepository grants,
            ConnectorConnectionRepository connections,
            ConnectorBindingRepository bindings,
            AppUserRepository users,
            ConnectorCatalogCache catalog,
            HermesConnectorClient connector,
            DelegationProperties delegation,
            ApplicationEventPublisher events,
            NotificationService notifications,
            ConversationNotices conversations,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.actions = actions;
        this.grants = grants;
        this.connections = connections;
        this.bindings = bindings;
        this.users = users;
        this.catalog = catalog;
        this.connector = connector;
        this.delegation = delegation;
        this.events = events;
        this.notifications = notifications;
        this.conversations = conversations;
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
                .map(action -> view(action, manifests.computeIfAbsent(action.connectorId(), this::readManifest)))
                .toList();
    }

    /**
     * 대화마다 승인 줄의 결과를 전한 가장 늦은 시각이다. 전한 줄이 없는 대화는 빠진다. 먼저 알리기가 할 일에 연결한 대화의 결과
     * 도착을 볼 때 읽는다.
     *
     * <p>실행한 줄({@code SUCCEEDED}, {@code FAILED}, {@code UNKNOWN})만 센다. 거절하거나 만료한 줄도 알림 줄을 남기며 전한 시각을
     * 적지만, 승인한 동작의 결과가 아니다.
     *
     * <p>대화마다 읽지 않고 집계 한 번으로 읽는다. 번호가 비면 읽지 않는다.
     */
    @Transactional(readOnly = true)
    public Map<Long, Instant> lastResultDeliveredAt(Collection<Long> conversationIds) {
        if (conversationIds.isEmpty()) {
            return Map.of();
        }
        return actions.findLastDeliveredByConversation(conversationIds, EXECUTED).stream()
                .collect(Collectors.toMap(ActionDelivery::getConversationId, ActionDelivery::getDeliveredAt));
    }

    /**
     * 그 사용자의 답을 기다리는 승인 줄을 만든 순으로 준다. 먼저 알리기의 판정이 읽는다.
     *
     * <p>사람에게 보일 이름은 승인 카드와 같은 규칙이다. 커넥터마다 카탈로그를 한 번만 읽는다. 인자와 결과 글은 담지 않는다.
     */
    @Transactional(readOnly = true)
    public List<PendingApproval> pendingApprovalsOf(CurrentUser user) {
        Map<String, Optional<ConnectorManifest>> manifests = new HashMap<>();
        return actions.findByUserIdAndStatusOrderByIdAsc(user.id(), ActionStatus.PENDING).stream()
                .map(action -> new PendingApproval(
                        action.publicId(),
                        view(action, manifests.computeIfAbsent(action.connectorId(), this::readManifest))
                                .title(),
                        action.agentId(),
                        action.conversationId(),
                        action.createdAt(),
                        action.expiresAt()))
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

    /** 그 대화에서 결과를 아직 전하지 않은 승인 줄이다. 실행을 보낸 뒤 끝난 것만이고 만든 순이다. */
    @Transactional(readOnly = true)
    public List<ConnectorActionResult> undeliveredResults(Long conversationId) {
        return undelivered(conversationId, EXECUTED);
    }

    /**
     * 그 대화와 그 사용자의 실행한 승인 줄을 번호의 순서로 다시 읽는다. 결과를 다시 전달할 때 쓴다(ADR-075).
     *
     * <p>전했는지는 보지 않는다. 없거나 남의 줄이거나 실행을 보내지 않고 끝난 줄은 뺀다. 실행을 다시 보내지 않는다.
     */
    @Transactional(readOnly = true)
    public List<ConnectorActionResult> resultsFor(Long conversationId, Long userId, List<UUID> actionIds) {
        if (actionIds.isEmpty()) {
            return List.of();
        }
        Map<UUID, ConnectorAction> found = new HashMap<>();
        actions.findByConversationIdAndUserIdAndPublicIdIn(conversationId, userId, actionIds).stream()
                .filter(action -> EXECUTED.contains(action.status()))
                .forEach(action -> found.put(action.publicId(), action));
        Map<String, Optional<ConnectorManifest>> manifests = new HashMap<>();
        return actionIds.stream()
                .distinct()
                .map(found::get)
                .filter(Objects::nonNull)
                .map(action -> resultOf(action, manifests))
                .toList();
    }

    /** 그 대화에서 알림 줄만 남길 줄이다. 실행하지 않고 끝났고 아직 전하지 않은 것이다. */
    @Transactional(readOnly = true)
    public List<ConnectorActionResult> undeliveredClosures(Long conversationId) {
        return undelivered(conversationId, CLOSED_WITHOUT_EXECUTION);
    }

    /** 대화에 전했다고 적는다. 이미 전한 줄은 건드리지 않는다. 부르는 쪽의 트랜잭션에 함께 묶인다. */
    @Transactional
    public void markDelivered(List<UUID> actionIds, Instant now) {
        if (!actionIds.isEmpty()) {
            actions.markDelivered(actionIds, now);
        }
    }

    /**
     * 그 줄을 전하는 일을 이 호출이 맡았는가. 먼저 적은 쪽만 참을 받는다.
     *
     * <p>같은 줄의 사건이 겹쳐 와도 알림 줄을 한 번만 남기게 한다.
     */
    @Transactional
    public boolean claimDelivery(UUID actionId, Instant now) {
        return actions.markDelivered(List.of(actionId), now) == 1;
    }

    /** 실행한 결과를 아직 전하지 않은 대화들이다. 기동할 때 훑는다. */
    @Transactional(readOnly = true)
    public List<Long> conversationsWithUndelivered() {
        return actions.findConversationsWithUndelivered(EXECUTED);
    }

    private List<ConnectorActionResult> undelivered(Long conversationId, Set<ActionStatus> statuses) {
        Map<String, Optional<ConnectorManifest>> manifests = new HashMap<>();
        return actions
                .findByConversationIdAndStatusInAndResultDeliveredAtIsNullOrderByIdAsc(conversationId, statuses)
                .stream()
                .map(action -> resultOf(action, manifests))
                .toList();
    }

    /** @param manifests 커넥터마다 한 번만 읽으려고 부르는 쪽이 들고 있는 manifest 들 */
    private ConnectorActionResult resultOf(ConnectorAction action, Map<String, Optional<ConnectorManifest>> manifests) {
        ConnectorActionView view = view(action, manifests.computeIfAbsent(action.connectorId(), this::readManifest));
        return new ConnectorActionResult(
                action.publicId(),
                view.title(),
                action.toolName() == null ? action.hermesTool() : action.toolName(),
                action.status(),
                action.errorCode(),
                action.resultText(),
                action.userId(),
                action.executedAt());
    }

    /**
     * 내 상시 허락 가운데 유효한 것이다.
     *
     * <p>지금 선언이 상시 허락을 닫은 도구의 줄은 내지 않는다(ADR-065). 판정이 그 줄을 보지 않아 효력이 없다.
     * 그런 줄은 {@link #revokeClosedGrants} 가 곧 거둔다. 카탈로그를 읽지 못했으면 낸다.
     */
    @Transactional(readOnly = true)
    public List<ConnectorGrantView> grants(CurrentUser user) {
        Map<String, Optional<ConnectorManifest>> manifests = new HashMap<>();
        List<ConnectorGrantView> views = new ArrayList<>();
        for (ConnectorToolGrant grant : grants.findActiveByUserId(user.id(), now())) {
            Optional<ToolPolicy> declared = manifests
                    .computeIfAbsent(grant.connectorId(), this::readManifest)
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
    public int revokeClosedGrants(Instant now) {
        Map<String, Optional<ConnectorManifest>> manifests = new HashMap<>();
        List<Long> closed = grants.findActive(now).stream()
                .filter(grant -> manifests
                        .computeIfAbsent(grant.connectorId(), this::readManifest)
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
     * <p>만료와 그 알림은 한 트랜잭션이다. 알림의 도구 제목을 얻으려 카탈로그를 읽는데, 행을 잠근 트랜잭션 안에서
     * 읽지 않으려고 만료할 줄의 커넥터마다 한 번씩 먼저 읽어 둔다. 대화 없는 줄은 알림을 남기지 않으므로 그 커넥터는
     * 읽지 않는다.
     *
     * @return 만료로 바꾼 건수
     */
    public int expire(Instant now) {
        List<ConnectorAction> dueActions = actions.findByStatusAndExpiresAtBefore(ActionStatus.PENDING, now);
        Map<String, Optional<ConnectorManifest>> manifests = new HashMap<>();
        dueActions.stream()
                .filter(due -> due.conversationId() != null)
                .forEach(due -> manifests.computeIfAbsent(due.connectorId(), this::readManifest));
        int expired = 0;
        for (ConnectorAction due : dueActions) {
            try {
                // 줄을 잠그고 다시 읽는다. 그 사이 승인이나 거절이 끝났으면 건드리지 않는다.
                Optional<ConnectorAction> changed =
                        transactions.execute(status -> actions.findByPublicIdForUpdate(due.publicId())
                                .filter(action -> action.status() == ActionStatus.PENDING
                                        && action.expiresAt().isBefore(now))
                                .map(action -> {
                                    action.expire(now);
                                    ConnectorAction saved = actions.save(action);
                                    notifyExpired(saved, manifests.getOrDefault(saved.connectorId(), Optional.empty()));
                                    return saved;
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
     * 실행을 보낸 지 오래됐는데 {@code EXECUTING} 으로 남은 줄을 결과를 모르는 것으로 바꾼다. 다시 실행하지 않는다.
     *
     * <p>실행은 끝났는데 결과를 적는 저장이 실패하면 줄이 이 상태로 남고, 그 연결의 해제와 다시 등록이 다음 기동까지
     * 막힌다. 결과를 적는 쪽은 {@code EXECUTING} 이 아닌 줄을 건드리지 않으므로 늦게 온 결과와 겹쳐도 안전하다.
     *
     * @param before 이 시각보다 먼저 승인한 줄만 바꾼다. 실행의 시간 제한보다 넉넉히 앞선 시각을 준다
     * @return 바꾼 건수
     */
    public int markStale(Instant before, Instant now) {
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
                    publish(unknown.get());
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
        publishAfterCommit(rejected);
    }

    /**
     * 그 에이전트에서 연결을 뗄 때 그 에이전트의 실행이 판정한 줄만 거절한다. 트랜잭션 안에서 부른다.
     *
     * <p>상시 허락은 건드리지 않는다. 허락은 사용자와 커넥터에 묶여 같은 연결을 붙인 다른 에이전트에도 걸린다.
     * {@code EXECUTING} 이 남은 줄이 있으면 {@code CONNECTOR_ACTION_EXECUTING} 으로 거절한다. 실행이 그 profile 의 값으로
     * 돌고 있어 그 사이 떼면 결과를 알 수 없다. 부르는 쪽이 사용자 행을 잠근 채 부르고 승인도 같은 행을 먼저 잠그므로
     * 줄을 따로 잠그지 않는다.
     */
    public void rejectPendingFor(ConnectorConnection connection, Long agentId, Instant now) {
        if (!actions.findByUserIdAndConnectorIdAndAgentIdAndStatus(
                        connection.userId(), connection.connectorId(), agentId, ActionStatus.EXECUTING)
                .isEmpty()) {
            throw new ApiException(ErrorCode.CONNECTOR_ACTION_EXECUTING, EXECUTING_MESSAGE);
        }
        List<ConnectorAction> pending = actions.findByUserIdAndConnectorIdAndAgentIdAndStatus(
                connection.userId(), connection.connectorId(), agentId, ActionStatus.PENDING);
        pending.forEach(action -> action.refuse(ConnectorAction.CONNECTION_CHANGED, now));
        publishAfterCommit(actions.saveAll(pending));
    }

    /** 거절한 줄의 사건을 트랜잭션이 커밋한 뒤에 낸다. 트랜잭션 밖이면 바로 낸다. */
    private void publishAfterCommit(List<ConnectorAction> rejected) {
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
     * <p>기다리는 시간이 지났거나, 연결이 준비되지 않았거나, 판정한 실행의 에이전트에서 그 연결을 떼었거나, 지금 정책으로는
     * 실행할 수 없는 줄은 실행하지 않고 끝낸다. 실행은 그 줄의 에이전트에 붙은 바인딩의 profile 에서 한다(ADR-083). 그 상태를 커밋해야 하므로 예외로 알리지 않고 profile 없는 결과로 돌려준다. 정책은 요청을 만들 때가
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
            ConnectorAction saved = actions.save(action);
            notifyExpired(saved, readManifest(saved.connectorId()));
            return Approval.refused(saved);
        }
        Optional<ConnectorManifest> manifest = readManifest(action.connectorId());
        // 승인 줄이 `hiddenArgs` 로 낸 것과 같은 조건이다. 사람이 다 읽지 못한 인자로는 실행하지 않는다. 기간을 실은
        // 승인도 같으므로 허락을 볼 것보다 먼저 본다.
        if (hiddenArgs(manifest, action)) {
            action.refuse(ConnectorAction.HIDDEN_ARGS, now);
            return Approval.refused(actions.save(action));
        }
        // 승인 줄이 `grantAllowed` 로 낸 것과 같은 조건이다. 카탈로그를 읽지 못했으면 허락을 줄 수 없다.
        if (grant != null && !(action.grantAllowed() && grantable(manifest, action))) {
            throw grantNotAllowed();
        }
        // 붙이고 떼는 쪽도 사용자 행을 먼저 잠그므로 여기서 본 바인딩은 커밋할 때까지 떼어지지 않는다.
        Optional<ConnectorBinding> binding = connections
                .findByUserIdAndConnectorId(action.userId(), action.connectorId())
                .flatMap(found -> bindings.findByAgentIdAndConnectionId(action.agentId(), found.id()));
        Optional<ToolPolicyDecision> decision =
                binding.flatMap(found -> manifest.map(declared -> redecide(found, declared, action)));
        if (decision.isEmpty() || decision.get().decision() == ActionDecision.DENIED) {
            action.refuse(ConnectorAction.NOT_EXECUTABLE, now);
            return Approval.refused(actions.save(action));
        }
        if (grant != null) {
            // 지금 정책으로 다시 본다. 요청을 만든 뒤 늘 승인을 받는 도구로 바뀌었거나 선언이 상시 허락을 닫았으면
            // 허락을 주지 않는다.
            if (decision.get().approval() != ToolApproval.REQUIRED || !grantable(manifest, action)) {
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
        return new Approval(actions.save(action), binding.get().agent().hermesProfile());
    }

    /**
     * 상시 허락은 없는 것으로 두고 지금 정책으로 다시 판정한다. 허락이 있어 통과하는 호출은 승인 줄이 되지 않는다.
     *
     * <p>연결 상태는 판정과 같은 규칙이다. 연결과 바인딩이 모두 {@code READY} 가 아니면 준비되지 않은 연결로 판정해 막는다.
     *
     * <p>살펴보기의 경계는 보지 않는다. 읽기 경계의 살펴보기는 승인 줄을 만들지 않고, 쓰기 도구를 허용한 살펴보기(ADR-082)의 승인
     * 줄은 사람이 승인한 것이라 보통 실행의 줄과 같게 판정한다.
     */
    private static ToolPolicyDecision redecide(
            ConnectorBinding binding, ConnectorManifest manifest, ConnectorAction action) {
        return ToolPolicyDecision.decide(
                ConnectorPolicyService.usableStatus(binding.connection(), binding),
                ConnectorPolicyService.ownServerTool(manifest, action.hermesTool()),
                manifest.schema(),
                ConnectorToolPolicies.find(manifest, action.toolName()),
                false,
                action.argsJson().getBytes(StandardCharsets.UTF_8).length,
                CheckBoundary.NOT_CHECK);
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
        return view(action, readManifest(action.connectorId()));
    }

    /** @param manifest 그 줄의 커넥터 manifest. 읽지 못했으면 빈 값 */
    private static ConnectorActionView view(ConnectorAction action, Optional<ConnectorManifest> manifest) {
        return ConnectorActionView.from(
                action,
                manifest.flatMap(found -> ConnectorToolPolicies.find(found, action.toolName())),
                grantable(manifest, action),
                hiddenArgs(manifest, action));
    }

    /**
     * 지금 카탈로그로 볼 때 그 줄의 도구에 상시 허락을 줄 수 있는가(ADR-065).
     *
     * <p>승인 줄에 저장하지 않고 읽을 때마다 본다. 선언이 바뀌면 바로 따른다. 카탈로그를 읽지 못했으면 줄 수 없는
     * 것으로 낸다. 도구를 선언하지 않는 판은 상시 허락을 닫는 선언을 둘 수 없으므로 선언 없는 도구에도 줄 수 있다.
     * 도구를 선언하는 판에서 선언이 없는 도구는 호출이 거절되므로 줄 수 없다.
     *
     * @param manifest 그 줄의 커넥터 manifest. 읽지 못했으면 빈 값
     */
    private static boolean grantable(Optional<ConnectorManifest> manifest, ConnectorAction action) {
        if (manifest.isEmpty()) {
            return false;
        }
        return ConnectorToolPolicies.find(manifest.get(), action.toolName())
                .map(ToolPolicy::grantable)
                .orElse(manifest.get().schema() == SCHEMA_WITHOUT_TOOLS);
    }

    /**
     * 지금 카탈로그로 볼 때 그 줄의 도구가 상시 허락을 닫은 도구인가(ADR-065).
     *
     * <p>승인을 받는 도구이고 선언이 상시 허락을 닫았을 때다. 밖으로 나가는 호출이라 사람이 인자를 다 읽어야 한다.
     * 카탈로그를 읽지 못했으면 닫았는지 알 수 없으므로 거짓이다. 그 줄은 판정이 이미 {@code NOT_EXECUTABLE} 로
     * 끝내 실행되지 않는다. 선언 없는 도구와 상시 허락을 줄 수 있는 도구도 거짓이다.
     *
     * @param manifest 그 줄의 커넥터 manifest. 읽지 못했으면 빈 값
     */
    private static boolean grantClosed(Optional<ConnectorManifest> manifest, ConnectorAction action) {
        return manifest.flatMap(found -> ConnectorToolPolicies.find(found, action.toolName()))
                .filter(declared -> declared.approval() == ToolApproval.REQUIRED && !declared.grantable())
                .isPresent();
    }

    /**
     * 답을 기다리는 줄인데 사람이 인자를 다 읽을 수 없는가. 상시 허락을 닫은 도구의 인자에 화면에서 가려지는 글이
     * 있을 때다. 이런 줄은 승인할 수 없다.
     *
     * <p>카탈로그를 읽지 못했으면 거짓이다. 그 도구가 상시 허락을 닫았는지 모르는 채 다른 커넥터의 줄에도 경고를 붙이면
     * 화면이 알리는 까닭이 틀린다. 실행은 {@link #beginApproval} 이 지금 정책을 판정하지 못한 줄로 막는다.
     */
    private static boolean hiddenArgs(Optional<ConnectorManifest> manifest, ConnectorAction action) {
        return action.status() == ActionStatus.PENDING
                && grantClosed(manifest, action)
                && ToolDetailRedactor.hidesArguments(action.argsJson());
    }

    /**
     * 만료한 승인 줄의 주인에게 알린다. 그 줄을 만료로 바꾼 트랜잭션 안에서 부른다.
     *
     * <p>대화 없이 돈 실행의 줄과, 대화가 지워졌거나 없는 줄은 눌러도 갈 곳이 없어 남기지 않는다. 도구 제목은 승인 카드와
     * 같은 규칙이다.
     *
     * @param manifest 그 줄의 커넥터 manifest. 읽지 못했으면 빈 값
     */
    private void notifyExpired(ConnectorAction action, Optional<ConnectorManifest> manifest) {
        if (action.conversationId() == null) {
            return;
        }
        String toolTitle = ConnectorActionView.titleOf(
                manifest.flatMap(found -> ConnectorToolPolicies.find(found, action.toolName())));
        conversations
                .publicIdOf(action.conversationId())
                .ifPresent(conversationId -> notifications.notify(
                        action.userId(),
                        NotificationKind.APPROVAL_EXPIRED,
                        APPROVAL_EXPIRED_TITLE,
                        "「" + toolTitle + "」",
                        new NotificationTarget(NotificationTargetType.CONVERSATION, conversationId)));
    }

    /** 읽지 못했거나 카탈로그에 없으면 빈 값이다. 예외 메시지에는 원격 응답이 섞일 수 있어 종류만 남긴다. */
    private Optional<ConnectorManifest> readManifest(String connectorId) {
        try {
            return catalog.find(connectorId);
        } catch (RuntimeException ex) {
            log.warn(
                    "connector {} catalog read failed: {}",
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
