package com.bifos.assistant.connector.application;

import com.bifos.assistant.chat.application.ConversationNotices;
import com.bifos.assistant.connector.application.model.ConnectorActionResult;
import com.bifos.assistant.connector.application.model.ConnectorActionView;
import com.bifos.assistant.connector.application.model.ConnectorGrantView;
import com.bifos.assistant.connector.application.model.PendingApproval;
import com.bifos.assistant.connector.domain.ConnectorConnection;
import com.bifos.assistant.connector.domain.type.GrantPeriod;
import com.bifos.assistant.connector.infra.ConnectorActionRepository;
import com.bifos.assistant.connector.infra.ConnectorBindingRepository;
import com.bifos.assistant.connector.infra.ConnectorConnectionRepository;
import com.bifos.assistant.connector.infra.ConnectorToolGrantRepository;
import com.bifos.assistant.feedback.application.DecisionFeedbackRecorder;
import com.bifos.assistant.hermes.HermesConnectorClient;
import com.bifos.assistant.notification.application.NotificationService;
import com.bifos.assistant.orchestration.application.DelegationProperties;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
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
@Service
public class ConnectorActionService {
    private static final String NOT_FOUND_MESSAGE = "this connector action does not exist";

    /** 만료한 승인 줄을 알리는 알림의 제목이다. 본문은 도구 제목이다. */
    static final String APPROVAL_EXPIRED_TITLE = "승인 요청이 만료됐어요";

    private final ConnectorActionDetails details;
    private final ConnectorActionApproval approval;
    private final ConnectorActionLifecycle lifecycle;

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
            DecisionFeedbackRecorder feedback,
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
                feedback,
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
            DecisionFeedbackRecorder feedback,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        this.details = new ConnectorActionDetails(actions, catalog);
        ConnectorActionSignals signals = new ConnectorActionSignals(events, notifications, conversations);
        this.approval = new ConnectorActionApproval(
                actions,
                grants,
                connections,
                bindings,
                users,
                connector,
                delegation,
                feedback,
                details,
                signals,
                transactions,
                clock);
        this.lifecycle = new ConnectorActionLifecycle(actions, grants, details, signals, transactions, clock);
    }

    /**
     * 그 대화의 승인 줄이다. 답을 기다리는 줄 전부와 끝난 줄 가운데 최근 20개를 만든 순으로 준다.
     *
     * @param conversationId 주인인 것을 이미 확인한 대화의 번호
     */
    @Transactional(readOnly = true)
    public List<ConnectorActionView> listForConversation(CurrentUser user, Long conversationId) {
        return details.listForConversation(user, conversationId);
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
        return details.lastResultDeliveredAt(conversationIds);
    }

    /**
     * 그 사용자의 답을 기다리는 승인 줄을 만든 순으로 준다. 먼저 알리기의 판정이 읽는다.
     *
     * <p>사람에게 보일 이름은 승인 카드와 같은 규칙이다. 커넥터마다 카탈로그를 한 번만 읽는다. 인자와 결과 글은 담지 않는다.
     */
    @Transactional(readOnly = true)
    public List<PendingApproval> pendingApprovalsOf(CurrentUser user) {
        return details.pendingApprovalsOf(user);
    }

    /** 그 대화에서 결과를 아직 전하지 않은 승인 줄이다. 실행을 보낸 뒤 끝난 것만이고 만든 순이다. */
    @Transactional(readOnly = true)
    public List<ConnectorActionResult> undeliveredResults(Long conversationId) {
        return details.undeliveredResults(conversationId);
    }

    /**
     * 그 대화와 그 사용자의 실행한 승인 줄을 번호의 순서로 다시 읽는다. 결과를 다시 전달할 때 쓴다(ADR-075).
     *
     * <p>전했는지는 보지 않는다. 없거나 남의 줄이거나 실행을 보내지 않고 끝난 줄은 뺀다. 실행을 다시 보내지 않는다.
     */
    @Transactional(readOnly = true)
    public List<ConnectorActionResult> resultsFor(Long conversationId, Long userId, List<UUID> actionIds) {
        return details.resultsFor(conversationId, userId, actionIds);
    }

    /** 그 대화에서 알림 줄만 남길 줄이다. 실행하지 않고 끝났고 아직 전하지 않은 것이다. */
    @Transactional(readOnly = true)
    public List<ConnectorActionResult> undeliveredClosures(Long conversationId) {
        return details.undeliveredClosures(conversationId);
    }

    /** 대화에 전했다고 적는다. 이미 전한 줄은 건드리지 않는다. 부르는 쪽의 트랜잭션에 함께 묶인다. */
    @Transactional
    public void markDelivered(List<UUID> actionIds, Instant now) {
        details.markDelivered(actionIds, now);
    }

    /**
     * 그 줄을 전하는 일을 이 호출이 맡았는가. 먼저 적은 쪽만 참을 받는다.
     *
     * <p>같은 줄의 사건이 겹쳐 와도 알림 줄을 한 번만 남기게 한다.
     */
    @Transactional
    public boolean claimDelivery(UUID actionId, Instant now) {
        return details.claimDelivery(actionId, now);
    }

    /** 실행한 결과를 아직 전하지 않은 대화들이다. 기동할 때 훑는다. */
    @Transactional(readOnly = true)
    public List<Long> conversationsWithUndelivered() {
        return details.conversationsWithUndelivered();
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
        return approval.approve(user, actionId, grant);
    }

    /** 승인 줄을 거절한다. 실행하지 않는다. */
    public ConnectorActionView reject(CurrentUser user, UUID actionId) {
        return approval.reject(user, actionId);
    }

    /**
     * 내 상시 허락 가운데 유효한 것이다.
     *
     * <p>지금 선언이 상시 허락을 닫은 도구의 줄은 내지 않는다(ADR-065). 판정이 그 줄을 보지 않아 효력이 없다.
     * 그런 줄은 {@link #revokeClosedGrants} 가 곧 거둔다. 카탈로그를 읽지 못했으면 낸다.
     */
    @Transactional(readOnly = true)
    public List<ConnectorGrantView> grants(CurrentUser user) {
        return lifecycle.grants(user);
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
        return lifecycle.revokeClosedGrants(now);
    }

    /** 허락을 거둔다. 남의 허락은 없는 것과 같은 응답이다. 있는지를 알리지 않는다. */
    @Transactional
    public void revokeGrant(CurrentUser user, Long grantId) {
        lifecycle.revokeGrant(user, grantId);
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
        return lifecycle.expire(now);
    }

    /**
     * 실행을 보낸 채 서버가 내려가 {@code EXECUTING} 으로 남은 줄을 결과를 모르는 것으로 바꾼다. 다시 실행하지 않는다.
     *
     * @return 바꾼 건수
     */
    public int markInterrupted(Instant now) {
        return lifecycle.markInterrupted(now);
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
        return lifecycle.markStale(before, now);
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
        lifecycle.rejectPendingFor(connection, now);
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
        lifecycle.rejectPendingFor(connection, agentId, now);
    }

    static ApiException notFound() {
        return new ApiException(ErrorCode.CONNECTOR_ACTION_NOT_FOUND, NOT_FOUND_MESSAGE);
    }
}
