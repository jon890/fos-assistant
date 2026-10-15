package com.bifos.assistant.connector.application;

import com.bifos.assistant.chat.application.ConversationNotices;
import com.bifos.assistant.connector.application.model.ConnectorActionView;
import com.bifos.assistant.connector.application.model.FinancialLimitChangeSummary;
import com.bifos.assistant.connector.application.model.FinancialLimitsView;
import com.bifos.assistant.connector.domain.ConnectorAction;
import com.bifos.assistant.connector.domain.ConnectorBinding;
import com.bifos.assistant.connector.domain.FinancialLimitChange;
import com.bifos.assistant.connector.domain.ToolPolicy;
import com.bifos.assistant.connector.domain.ToolPolicyDecision;
import com.bifos.assistant.connector.domain.type.ActionDecision;
import com.bifos.assistant.connector.domain.type.ActionStatus;
import com.bifos.assistant.connector.domain.type.BindingStatus;
import com.bifos.assistant.connector.domain.type.ConnectionStatus;
import com.bifos.assistant.connector.domain.type.FinancialLimitKind;
import com.bifos.assistant.connector.domain.type.ToolApproval;
import com.bifos.assistant.connector.domain.type.ToolRisk;
import com.bifos.assistant.connector.infra.ConnectorActionBodies;
import com.bifos.assistant.connector.infra.ConnectorActionRepository;
import com.bifos.assistant.connector.infra.ConnectorBindingRepository;
import com.bifos.assistant.connector.infra.FinancialLimitChangeRepository;
import com.bifos.assistant.mcp.application.FinancialLimitRequests;
import com.bifos.assistant.notification.application.NotificationService;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.config.LiveProperties;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.shared.util.Sha256;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * 에이전트가 대화로 주문 상한을 바꾸는 길과 그 승인이다(ADR-20261015 / financial-user-limits).
 *
 * <p>낮추기와 같은 값은 바로 반영한다. 더 닫히는 쪽이라 승인이 없어도 주문이 늘지 않는다. 올리기는 PENDING 승인 줄과 딸린 줄만
 * 만들고, 계좌 주인이 카드에서 승인해야 반영한다. 에이전트가 승인 없이 상한을 올리는 길은 이 클래스에 없다.
 *
 * <p>승인 줄의 위험도는 {@code WRITE} 다. {@code FINANCIAL} 로 두면 금융 관문의 하루 합계가 실행 내용이 없는 이 줄을 열지 못해 모든
 * 주문을 닫는다. 승인 방식은 {@code always} 라 상시 허락과 대화 허락이 없다.
 */
@Component
@RequiredArgsConstructor
public class FinancialLimitChanges implements FinancialLimitRequests {
    /** 승인 줄의 원래 도구 이름이다. MCP 도구 이름과 같다. */
    public static final String TOOL = "order_limit_change";
    /** 승인 줄의 등록 이름이다. 커넥터 도구가 아니므로 Hermes 가 부를 일이 없다. */
    static final String HERMES_TOOL = "fos_order_limit_change";
    static final String TITLE = "주문 상한 변경";
    /** 카드가 쓰는 정책이다. 커넥터 선언이 아니라 Control Plane 이 정한다. */
    static final ToolPolicy POLICY = new ToolPolicy(ToolRisk.WRITE, ToolApproval.ALWAYS, TITLE, false, Set.of("name", "value"));
    private static final ToolPolicyDecision DECISION = new ToolPolicyDecision(ActionDecision.NEEDS_APPROVAL, null, ToolRisk.WRITE, ToolApproval.ALWAYS);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final FinancialLimitSettings settings;
    private final FinancialOrderLimits limits;
    private final AppUserRepository users;
    private final ConnectorBindingRepository bindings;
    private final ConnectorCatalogCache catalog;
    private final AgentExecutionRepository executions;
    private final ConnectorActionRepository actions;
    private final FinancialLimitChangeRepository changes;
    private final ConnectorActionBodies bodies;
    private final LiveProperties<ConnectorPolicyProperties> policy;
    private final ApplicationEventPublisher events;
    private final NotificationService notifications;
    private final ConversationNotices conversations;
    private final Clock clock;


    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> read(CurrentUser user) {
        FinancialLimitsView view = settings.view(user, clock.instant());
        var limitsOut = view.limits().stream().map(item -> {
            var entry = new LinkedHashMap<String, Object>();
            entry.put("name", item.name());
            entry.put("label", item.label());
            entry.put("currency", item.currency());
            entry.put("value", item.value());
            entry.put("maximum", item.maximum());
            entry.put("custom", item.custom());
            return entry;
        }).toList();
        var result = new LinkedHashMap<String, Object>();
        result.put("available", view.available());
        result.put("limits", limitsOut);
        result.put("spentToday", view.spentToday());
        result.put("remainingToday", view.remainingToday());
        return result;
    }


    @Override
    @Transactional
    public Map<String, Object> change(CurrentUser user, Long originExecutionId, String name, String value) {
        var kind = FinancialLimitKind.fromApiName(name)
            .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED, "unknown financial limit"));
        BigDecimal requested = new BigDecimal(value);
        FinancialLimitSettings.requireShape(requested);
        if (!settings.hasAccount(user.email())) {
            return Map.of("status", "unavailable");
        }
        // 주문 판정과 같은 행을 잠근다. 상한 변경과 주문 판정이 차례로 일어난다.
        users.findByIdForUpdate(user.id()).orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN, "no user"));
        var values = limits.effective(user.id());
        BigDecimal maximum = values.maximum(kind);
        BigDecimal current = values.value(kind);
        if (requested.compareTo(maximum) > 0) {
            return Map.of("status", "above_maximum", "name", name, "maximum", FinancialOrderLimits.plain(maximum));
        }
        Instant now = clock.instant();
        if (requested.compareTo(current) <= 0) {
            limits.store(user.id(), kind, requested, now);
            return Map.of("status", "applied", "name", name, "previous", FinancialOrderLimits.plain(current),
                "value", FinancialOrderLimits.plain(requested));
        }
        var origin = executions.findById(originExecutionId)
            .orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN, "no origin execution"));
        var binding = financialBinding(user, origin.agentId());
        if (binding.isEmpty()) {
            return Map.of("status", "no_financial_connection");
        }
        String plain = FinancialOrderLimits.plain(requested);
        String dedupeKey = Sha256.hex("fos-order-limit:" + user.id() + ":" + originExecutionId + ":" + kind + ":" + plain);
        var existing = actions.findByDedupeKey(dedupeKey);
        if (existing.isPresent()) {
            // 끝난 카드를 기다리는 카드처럼 안내하지 않는다. 거절이나 만료로 끝났으면 그 상태를 알린다.
            if (existing.get().status() != ActionStatus.PENDING) {
                return Map.of("status", "closed", "actionId", existing.get().publicId().toString(), "actionStatus",
                    existing.get().status().name(), "message", "같은 요청의 승인 카드가 이미 끝났다. 상한은 바뀌지 않았다. "
                        + "다시 올리려면 사용자에게 확인한 뒤 사용자의 다음 요청에서 다시 부른다.");
            }
            return requested(existing.get(), name, current, requested, maximum);
        }
        String argsJson = JSON.writeValueAsString(Map.of("name", name, "value", plain));
        var action = ConnectorAction.decided(binding.get().connection(), origin.agentId(), origin, HERMES_TOOL, TOOL, DECISION,
            false, dedupeKey, Sha256.hex(argsJson), now);
        action.awaitApproval(now.plus(policy.current().approvalTtl()));
        actions.saveAndFlush(action);
        bodies.sealArgs(action, argsJson);
        changes.saveAndFlush(FinancialLimitChange.of(action.id(), user.id(), kind, requested, current, now));
        var signals = new ConnectorActionSignals(events, notifications, conversations);
        if (action.conversationId() != null) {
            signals.notifyRequested(action, TITLE);
        }
        signals.publishAfterCommit(List.of(action));
        return requested(action, name, current, requested, maximum);
    }


    /** 그 줄이 상한 변경 승인 줄인가. */
    boolean owns(ConnectorAction action) {
        return TOOL.equals(action.toolName()) && HERMES_TOOL.equals(action.hermesTool()) && changes.existsById(action.id());
    }


    /**
     * 상한 변경을 승인한다. 그 순간의 최대치로 다시 보고, 넘으면 반영하지 않고 실패로 끝낸다.
     * 사용자 행, 승인 줄 순서로 잠근다. 주문 판정과 같은 사용자 행이라 둘이 차례로 일어난다.
     */
    @Transactional
    public ConnectorActionView approve(CurrentUser user, UUID actionId) {
        users.findByIdForUpdate(user.id()).orElseThrow(ConnectorActionDetails::notFound);
        var action = actions.findByPublicIdForUpdate(actionId).orElseThrow(ConnectorActionDetails::notFound);
        if (!Objects.equals(action.userId(), user.id())) {
            throw ConnectorActionDetails.notFound();
        }
        Instant now = clock.instant();
        if (action.status() != ActionStatus.PENDING || !action.expiresAt().isAfter(now)) {
            throw new ApiException(ErrorCode.CONNECTOR_ACTION_NOT_PENDING, "limit change is not pending");
        }
        var change = changes.findById(action.id()).orElseThrow(ConnectorActionDetails::notFound);
        var values = limits.effective(user.id());
        action.beginExecution(now);
        String requested = FinancialOrderLimits.plain(change.requestedValue());
        if (change.requestedValue().compareTo(values.maximum(change.limitKind())) > 0) {
            bodies.fail(action, ErrorCode.FINANCIAL_LIMIT_ABOVE_MAXIMUM.name(), change.limitKind().label() + " 상한 " + requested
                + " 이 지금 최대치 " + FinancialOrderLimits.plain(values.maximum(change.limitKind())) + " 보다 커서 바꾸지 않았다", now);
        } else {
            limits.store(user.id(), change.limitKind(), change.requestedValue(), now);
            bodies.succeed(action, change.limitKind().label() + " 상한을 " + requested + " 으로 바꿨다", now);
        }
        new ConnectorActionSignals(events, notifications, conversations).publishAfterCommit(List.of(action));
        return ConnectorActionDetails.view(action, Optional.empty());
    }


    /** 줄 번호마다의 카드 요약이다. 없거나 남의 줄이거나 상한 변경 줄이 아니면 빠진다. */
    @Transactional(readOnly = true)
    public Map<UUID, FinancialLimitChangeSummary> summaries(CurrentUser user, List<UUID> actionIds) {
        var found = new LinkedHashMap<UUID, FinancialLimitChangeSummary>();
        for (UUID actionId : actionIds) {
            summaryOf(user, actionId).ifPresent(summary -> found.put(actionId, summary));
        }
        return found;
    }


    /** 그 줄의 카드 요약이다. */
    @Transactional(readOnly = true)
    public Optional<FinancialLimitChangeSummary> summaryOf(CurrentUser user, UUID actionId) {
        return actions.findByPublicId(actionId)
            .filter(action -> user.id().equals(action.userId()) && TOOL.equals(action.toolName()))
            .flatMap(action -> changes.findById(action.id()))
            .map(change -> {
                var values = limits.effective(user.id());
                var kind = change.limitKind();
                return new FinancialLimitChangeSummary(kind.apiName(), kind.currency(), kind.label(),
                    FinancialOrderLimits.plain(change.previousValue()), FinancialOrderLimits.plain(change.requestedValue()),
                    FinancialOrderLimits.plain(values.maximum(kind)), FinancialOrderLimits.plain(values.value(kind)));
            });
    }


    /**
     * 부른 에이전트에 붙은, 금융 보호를 선언한 연결의 바인딩이다. 정확히 하나일 때만 쓴다.
     * 금융 보호는 카탈로그 선언의 {@code execution_guard.prepare_tool} 로 가린다. 서비스 이름으로 고르지 않는다.
     */
    private Optional<ConnectorBinding> financialBinding(CurrentUser user, Long agentId) {
        var found = bindings.findByAgentId(agentId).stream()
            .filter(binding -> Objects.equals(binding.connection().userId(), user.id())
                && binding.status() == BindingStatus.READY
                && binding.connection().status() == ConnectionStatus.READY
                && catalog.find(binding.connection().connectorId())
                    .map(manifest -> manifest.executionGuard() != null && manifest.executionGuard().path("prepare_tool").isString())
                    .orElse(false))
            .toList();
        return found.size() == 1 ? Optional.of(found.getFirst()) : Optional.empty();
    }


    private static Map<String, Object> requested(ConnectorAction action, String name, BigDecimal previous, BigDecimal requested,
        BigDecimal maximum) {
        return Map.of("status", "approval_requested", "actionId", action.publicId().toString(), "name", name,
            "previous", FinancialOrderLimits.plain(previous), "requested", FinancialOrderLimits.plain(requested),
            "maximum", FinancialOrderLimits.plain(maximum),
            "message", "승인 카드를 만들었다. 사용자가 카드에서 승인해야 상한이 바뀐다. 아직 바뀌지 않았다.");
    }
}
