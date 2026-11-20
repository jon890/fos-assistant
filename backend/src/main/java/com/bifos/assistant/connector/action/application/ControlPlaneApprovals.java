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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
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
    private static final String UNAVAILABLE = "지금은 실행하지 못했어요. 잠시 뒤 다시 부탁해 주세요";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final ConnectorActionRepository actions;
    private final ConnectorActionBodies bodies;
    private final AgentExecutionRepository executions;
    private final AppUserRepository users;
    private final List<ControlPlaneActionRunner> runners;
    private final LiveProperties<ConnectorPolicyProperties> policy;
    private final ApplicationEventPublisher events;
    private final NotificationService notifications;
    private final ConversationNotices conversations;
    private final Clock clock;


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
            return new ControlPlaneApprovalRequest(existing.get().publicId(), false, existing.get().status());
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
     */
    @Transactional
    public ConnectorActionView approve(CurrentUser user, UUID actionId) {
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
        ControlPlaneActionOutcome outcome = run(user, action);
        if (outcome.succeeded()) {
            bodies.succeed(action, outcome.message(), now);
        } else {
            bodies.fail(action, FAILED, outcome.message(), now);
        }
        new ConnectorActionSignals(events, notifications, conversations).publishAfterCommit(List.of(action));
        return ConnectorActionDetails.view(action, Optional.empty());
    }


    private ControlPlaneActionOutcome run(CurrentUser user, ConnectorAction action) {
        Optional<Map<String, String>> args = action.executableArgs().flatMap(ControlPlaneApprovals::parse);
        Optional<ControlPlaneActionRunner> runner = runners.stream()
            .filter(candidate -> candidate.toolName().equals(action.toolName()))
            .findFirst();
        if (args.isEmpty() || runner.isEmpty()) {
            return ControlPlaneActionOutcome.failed(UNREADABLE);
        }
        try {
            return runner.get().run(user, args.get());
        } catch (RuntimeException ex) {
            log.warn("control plane action failed actionId={} kind={}", action.publicId(), ex.getClass().getSimpleName());
            return ControlPlaneActionOutcome.failed(UNAVAILABLE);
        }
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
