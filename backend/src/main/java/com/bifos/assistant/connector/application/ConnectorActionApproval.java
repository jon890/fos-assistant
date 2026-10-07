package com.bifos.assistant.connector.application;

import com.bifos.assistant.connector.application.model.ConnectorActionView;
import com.bifos.assistant.connector.domain.ConnectorAction;
import com.bifos.assistant.connector.domain.ConnectorBinding;
import com.bifos.assistant.connector.domain.ConnectorToolGrant;
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
import com.bifos.assistant.feedback.application.DecisionFeedbackRecorder;
import com.bifos.assistant.feedback.application.model.FeedbackEntry;
import com.bifos.assistant.feedback.domain.type.FeedbackActor;
import com.bifos.assistant.feedback.domain.type.FeedbackEventType;
import com.bifos.assistant.feedback.domain.type.FeedbackSubjectType;
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
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import static com.bifos.assistant.connector.application.ConnectorActionDetails.grantable;
import static com.bifos.assistant.connector.application.ConnectorActionDetails.hiddenArgs;

/** 승인의 두 트랜잭션과 그 사이 실행, 승인 직전 정책 확인을 맡는다. */
@Slf4j
class ConnectorActionApproval {
    private static final String NOT_PENDING_MESSAGE = "this connector action is not waiting for approval";

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
    private final HermesConnectorClient connector;
    private final DelegationProperties delegation;
    private final DecisionFeedbackRecorder feedback;
    private final ConnectorActionDetails details;
    private final ConnectorActionSignals signals;
    private final TransactionTemplate transactions;
    private final Clock clock;

    ConnectorActionApproval(
            ConnectorActionRepository actions,
            ConnectorToolGrantRepository grants,
            ConnectorConnectionRepository connections,
            ConnectorBindingRepository bindings,
            AppUserRepository users,
            HermesConnectorClient connector,
            DelegationProperties delegation,
            DecisionFeedbackRecorder feedback,
            ConnectorActionDetails details,
            ConnectorActionSignals signals,
            TransactionTemplate transactions,
            Clock clock) {
        this.actions = actions;
        this.grants = grants;
        this.connections = connections;
        this.bindings = bindings;
        this.users = users;
        this.connector = connector;
        this.delegation = delegation;
        this.feedback = feedback;
        this.details = details;
        this.signals = signals;
        this.transactions = transactions;
        this.clock = clock;
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
    ConnectorActionView approve(CurrentUser user, UUID actionId, GrantPeriod grant) {
        Approval approval = transactions.execute(status -> beginApproval(user, actionId, grant));
        // 사용자가 승인했다. 그 뒤 실행하지 못하고 끝난 줄도 사용자의 반응은 승인이다.
        feedback.record(actionFeedback(approval.action(), FeedbackEventType.APPROVED, FeedbackActor.USER)
                .reason(grant == null ? null : "WITH_GRANT"));
        if (approval.profile() == null) {
            // 실행하지 않고 끝낸 줄이다. 그 상태를 커밋한 뒤에 알린다.
            signals.publish(approval.action());
            return details.view(approval.action());
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
        signals.publish(finished);
        return details.view(finished);
    }

    /** 승인 줄을 거절한다. 실행하지 않는다. */
    ConnectorActionView reject(CurrentUser user, UUID actionId) {
        ConnectorAction rejected = transactions.execute(status -> {
            ConnectorAction action = requirePending(user, actionId);
            action.reject(now());
            return actions.save(action);
        });
        feedback.record(actionFeedback(rejected, FeedbackEventType.REJECTED, FeedbackActor.USER));
        signals.publish(rejected);
        return details.view(rejected);
    }

    /**
     * 줄을 잠그고 승인할 수 있는지 본 뒤 {@code EXECUTING} 으로 바꾼다. 트랜잭션 안에서만 부른다.
     *
     * <p>기다리는 시간이 지났거나, 연결이 준비되지 않았거나, 판정한 실행의 에이전트에서 그 연결을 떼었거나, 지금 정책으로는
     * 실행할 수 없는 줄은 실행하지 않고 끝낸다. 실행은 그 줄의 에이전트에 붙은 바인딩의 profile 에서 한다(ADR-083). 그 상태를 커밋해야 하므로 예외로 알리지 않고 profile 없는 결과로 돌려준다. 정책은 요청을 만들 때가
     * 아니라 지금 것으로 다시 본다. 그 사이 운영자가 도구를 선언에서 뺐거나 위험도를 올렸을 수 있다.
     */
    Approval beginApproval(CurrentUser user, UUID actionId, GrantPeriod grant) {
        Instant now = now();
        // 연결을 다시 등록하거나 해제하는 쪽과 같은 순서로 잠근다. 사용자 행이 먼저이고 승인 줄이 다음이다.
        // 그쪽이 값을 바꾸는 동안에는 여기서 기다리고, 그쪽이 커밋한 뒤에는 이 줄이 이미 PENDING 이 아니다.
        users.findByIdForUpdate(user.id()).orElseThrow(ConnectorActionService::notFound);
        ConnectorAction action = requirePending(user, actionId);
        if (!action.expiresAt().isAfter(now)) {
            action.expire(now);
            ConnectorAction saved = actions.save(action);
            signals.notifyExpired(saved, details.readManifest(saved.connectorId()));
            return Approval.refused(saved);
        }
        Optional<ConnectorManifest> manifest = details.readManifest(action.connectorId());
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
    ConnectorAction record(UUID actionId, CallResult result) {
        Instant now = now();
        ConnectorAction action =
                actions.findByPublicIdForUpdate(actionId).orElseThrow(ConnectorActionService::notFound);
        if (action.status() != ActionStatus.EXECUTING) {
            // 실행하는 동안 기동 정리가 이 줄을 이미 끝냈다. 그 상태를 그대로 둔다.
            return action;
        }
        if (result == null) {
            // 실행됐는지 모르는 줄은 성공도 실패도 아니라 판단 피드백 사건을 남기지 않는다.
            action.unknown(now);
        } else if (result.ok()) {
            action.succeed(clip(result.result().toString()), now);
            feedback.record(actionFeedback(action, FeedbackEventType.EXECUTION_SUCCEEDED, FeedbackActor.SYSTEM));
        } else {
            // 커넥터가 선언한 코드와 복구 계약만 저장한다. 외부 서비스의 오류 원문은 이 값에 들어오지 못한다(ADR-092).
            action.fail(
                    result.error().word(),
                    result.detail() == null ? null : result.detail().toStored(),
                    now);
            feedback.record(actionFeedback(action, FeedbackEventType.EXECUTION_FAILED, FeedbackActor.SYSTEM)
                    .reason(action.errorCode()));
        }
        return actions.save(action);
    }

    /**
     * 승인 줄의 판단 피드백 사건이다. 열쇠는 지금 화면의 {@code itemKey} 와 같고, 판은 인자 해시다. 인자와 결과 글은 담지 않는다.
     * 트랜잭션 안에서 부르면 커밋한 뒤에 남는다.
     */
    FeedbackEntry actionFeedback(ConnectorAction action, FeedbackEventType type, FeedbackActor actor) {
        return FeedbackEntry.of(
                        action.userId(), FeedbackSubjectType.CONNECTOR_ACTION, action.publicId(), type, actor, now())
                .conversation(action.conversationId())
                .originExecution(action.originExecutionId())
                .version(action.argsSha256());
    }

    /** 위임 답과 같은 상한으로 자른다. 상한 자리에서 대리 쌍이 나뉘면 그 앞에서 자른다. */
    String clip(String text) {
        int max = delegation.outputMaxChars();
        if (text.length() <= max) {
            return text;
        }
        return text.substring(0, Character.isHighSurrogate(text.charAt(max - 1)) ? max - 1 : max);
    }

    /** 줄을 잠그고 읽는다. 없는 줄과 남의 줄은 같은 응답이다. 트랜잭션 안에서만 부른다. */
    ConnectorAction requirePending(CurrentUser user, UUID actionId) {
        ConnectorAction action = actions.findByPublicIdForUpdate(actionId)
                .filter(found -> found.userId().equals(user.id()))
                .orElseThrow(ConnectorActionService::notFound);
        if (action.status() != ActionStatus.PENDING) {
            throw notPending();
        }
        return action;
    }

    Instant now() {
        return Instant.now(clock);
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
