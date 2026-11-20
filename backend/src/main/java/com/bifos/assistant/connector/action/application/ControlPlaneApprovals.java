package com.bifos.assistant.connector.action.application;

import com.bifos.assistant.chat.turn.application.ConversationNotices;
import com.bifos.assistant.connector.action.application.dto.ConnectorActionView;
import com.bifos.assistant.connector.action.domain.ConnectorAction;
import com.bifos.assistant.connector.action.domain.ControlPlaneActions;
import com.bifos.assistant.connector.action.domain.type.ActionStatus;
import com.bifos.assistant.connector.action.infra.ConnectorActionBodies;
import com.bifos.assistant.connector.action.infra.ConnectorActionRepository;
import com.bifos.assistant.connector.action.application.dto.ControlPlaneActionOutcome;
import com.bifos.assistant.connector.action.application.dto.ControlPlaneApprovalRequest;
import com.bifos.assistant.connector.action.application.port.ControlPlaneActionRunner;
import com.bifos.assistant.connector.connection.config.ConnectorPolicyProperties;
import com.bifos.assistant.notification.application.NotificationService;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.config.LiveProperties;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.shared.util.Sha256;
import com.bifos.assistant.usage.execution.infra.AgentExecutionRepository;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Control Plane 동작의 승인 카드를 띄우고, 사용자가 승인하면 그 동작을 한 번 한다(서비스 개념 설계의 「지우기 승인 카드」).
 *
 * <p>카드는 플러그인 승인 줄과 같은 {@code connector_action} 줄이라 자리, 건수, 승인 요청 알림, 만료, 거절 알림 줄을 그대로 쓴다.
 * 상시 허락과 대화 허락이 없다. 승인한 결과는 에이전트를 다시 돌리지 않고 알림 줄로만 남는다(스펙 DECISION21).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ControlPlaneApprovals {
    /** 동작이 예외로 끝난 줄의 {@code errorCode} 다. */
    static final String FAILED = "control_plane_failed";
    private static final String UNREADABLE = "요청 내용을 읽을 수 없어 실행하지 않았어요";
    private static final String INTERRUPTED = "요청을 처리하다 문제가 생겼어요. 그 화면에서 상태를 확인한 뒤 다시 요청해 주세요";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final ConnectorActionRepository actions;
    private final ConnectorActionBodies bodies;
    private final AgentExecutionRepository executions;
    private final AppUserRepository users;
    private final ObjectProvider<ControlPlaneActionRunner> runners;
    private final LiveProperties<ConnectorPolicyProperties> policy;
    private final ApplicationEventPublisher events;
    private final NotificationService notifications;
    private final ConversationNotices conversations;
    private final TransactionTemplate transactions;
    private final Clock clock;


    /**
     * 첫 트랜잭션의 결과다. 끝난 동작이면 {@code view} 만 있고, 트랜잭션 밖에서 돌 동작이면 실행기와 인자가 있다.
     */
    private record Begun(ConnectorActionView view, ControlPlaneActionRunner runner, Map<String, String> args) {
    }


    /**
     * 승인 카드를 띄운다. 같은 실행이 같은 대상으로 다시 부르면 새 카드를 만들지 않고 앞 카드의 상태를 돌려준다.
     *
     * @param originExecutionId 호출자가 검증한 현재 실행 번호. 카드는 그 실행의 대화에 뜬다
     * @param target 같은 요청을 알아볼 대상 식별자
     * @param args 카드에 보일 인자. 넣은 순서대로 보인다
     */
    @Transactional
    public ControlPlaneApprovalRequest request(
        CurrentUser user, Long originExecutionId, String toolName, String target, Map<String, String> args) {
        if (ControlPlaneActions.policy(toolName).isEmpty()) {
            throw new IllegalArgumentException("unknown control plane action: " + toolName);
        }
        var origin = executions.findById(originExecutionId)
            .orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN, "no origin execution"));
        String dedupeKey = Sha256.hex("fos-control-plane:" + toolName + ":" + user.id() + ":" + originExecutionId + ":" + target);
        var existing = actions.findByDedupeKey(dedupeKey);
        if (existing.isPresent()) {
            // 기한이 지났는데 아직 만료 처리 전인 줄은 만료로 알린다. 기다리는 카드처럼 안내하지 않는다.
            ConnectorAction found = existing.get();
            boolean lapsed = found.status() == ActionStatus.PENDING && !found.expiresAt().isAfter(clock.instant());
            return new ControlPlaneApprovalRequest(found.publicId(), false, lapsed ? ActionStatus.EXPIRED : found.status());
        }
        String argsJson = JSON.writeValueAsString(new LinkedHashMap<>(args));
        Instant now = clock.instant();
        var action = ConnectorAction.decided(user.id(), ControlPlaneActions.CONNECTOR_ID, origin.agentId(), origin,
            ControlPlaneActions.hermesTool(toolName), toolName, ControlPlaneActions.DECISION, false, dedupeKey, Sha256.hex(argsJson), now);
        action.awaitApproval(now.plus(policy.current().approvalTtl()));
        actions.saveAndFlush(action);
        bodies.sealArgs(action, argsJson);
        var signals = new ConnectorActionSignals(events, notifications, conversations);
        if (action.conversationId() != null) {
            signals.notifyRequested(action, ControlPlaneActions.policy(toolName).orElseThrow().title());
        }
        signals.publishAfterCommit(List.of(action));
        return new ControlPlaneApprovalRequest(action.publicId(), true, action.status());
    }


    /**
     * 승인하고 그 동작을 한 번 한다. 사용자 행, 승인 줄 순서로 잠근다. 남의 줄이거나 Control Plane 줄이 아니면 없는 줄과 같다.
     * 기다리는 줄이 아니거나 기한이 지났으면 {@code CONNECTOR_ACTION_NOT_PENDING} 이다.
     *
     * <p>이 메서드에 {@code @Transactional} 을 붙이지 않는다. 보통 동작은 승인과 실행, 결과가 한 트랜잭션이다. 실행기가
     * {@link ControlPlaneActionRunner#outsideTransaction()} 으로 알린 동작(연결 붙이기)은 {@code EXECUTING} 을 먼저 커밋하고 트랜잭션 밖에서
     * 실행한 뒤 다른 트랜잭션이 결과를 적는다(서비스 개념 설계의 「승인 뒤 붙이기」). 부르는 쪽도 트랜잭션 없이 부른다.
     */
    public ConnectorActionView approve(CurrentUser user, UUID actionId) {
        Begun begun = transactions.execute(status -> begin(user, actionId));
        if (begun.view() != null) {
            return begun.view();
        }
        ControlPlaneActionOutcome outcome;
        try {
            outcome = begun.runner().run(user, begun.args());
        } catch (RuntimeException ex) {
            // 승인은 이미 커밋됐다. 예외를 내보내면 줄이 EXECUTING 으로 남으므로 실패로 끝내 사용자가 다시 요청하게 한다.
            log.warn("control plane action failed actionId={} tool={} kind={}", actionId, begun.runner().toolName(), ex.getClass().getSimpleName());
            outcome = ControlPlaneActionOutcome.failed(INTERRUPTED);
        }
        ControlPlaneActionOutcome result = outcome;
        return transactions.execute(status -> finish(user, actionId, result));
    }


    /** 승인의 첫 트랜잭션이다. 트랜잭션 안에서 도는 동작은 여기서 끝내 화면 값을 돌려주고, 밖에서 도는 동작은 실행기와 인자를 돌려준다. */
    private Begun begin(CurrentUser user, UUID actionId) {
        users.findByIdForUpdate(user.id()).orElseThrow(ConnectorActionDetails::notFound);
        var action = actions.findByPublicIdForUpdate(actionId).orElseThrow(ConnectorActionDetails::notFound);
        if (!Objects.equals(action.userId(), user.id()) || !ControlPlaneActions.owns(action)) {
            throw ConnectorActionDetails.notFound();
        }
        Instant now = clock.instant();
        if (action.status() != ActionStatus.PENDING || !action.expiresAt().isAfter(now)) {
            throw new ApiException(ErrorCode.CONNECTOR_ACTION_NOT_PENDING, "control plane action is not pending");
        }
        action.beginExecution(now);
        Optional<Map<String, String>> args = action.executableArgs().flatMap(ControlPlaneApprovals::parse);
        Optional<ControlPlaneActionRunner> runner = runners.stream()
            .filter(candidate -> candidate.toolName().equals(action.toolName()))
            .findFirst();
        if (args.isPresent() && runner.isPresent() && runner.get().outsideTransaction()) {
            // 상태만 커밋한다. 결과를 적는 트랜잭션이 사건을 낸다.
            actions.save(action);
            return new Begun(null, runner.get(), args.get());
        }
        // 실행기의 저장소 예외는 잡지 않는다. 이 트랜잭션이 되돌림으로 표시돼 실패로 적어도 커밋되지 않는다.
        // 그대로 내보내 승인 전체를 되돌리고 카드는 기다리는 채로 남긴다. 사용자는 다시 승인할 수 있다.
        ControlPlaneActionOutcome outcome = args.isEmpty() || runner.isEmpty()
            ? ControlPlaneActionOutcome.failed(UNREADABLE)
            : runner.get().run(user, args.get());
        return new Begun(record(action, outcome, now), null, null);
    }


    /** 트랜잭션 밖에서 돈 동작의 결과를 적는다. 그 사이 정리가 줄을 끝냈으면 건드리지 않고 지금 상태를 돌려준다. */
    private ConnectorActionView finish(CurrentUser user, UUID actionId, ControlPlaneActionOutcome outcome) {
        users.findByIdForUpdate(user.id()).orElseThrow(ConnectorActionDetails::notFound);
        var action = actions.findByPublicIdForUpdate(actionId).orElseThrow(ConnectorActionDetails::notFound);
        if (action.status() != ActionStatus.EXECUTING) {
            return ConnectorActionDetails.view(action, Optional.empty());
        }
        return record(action, outcome, clock.instant());
    }


    private ConnectorActionView record(ConnectorAction action, ControlPlaneActionOutcome outcome, Instant now) {
        if (outcome.succeeded()) {
            bodies.succeed(action, outcome.message(), now);
        } else {
            bodies.fail(action, FAILED, outcome.message(), now);
        }
        new ConnectorActionSignals(events, notifications, conversations).publishAfterCommit(List.of(action));
        return ConnectorActionDetails.view(action, Optional.empty());
    }


    private static Optional<Map<String, String>> parse(String json) {
        try {
            return Optional.of(JSON.readValue(json, new TypeReference<Map<String, String>>() {
            }));
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }
}
