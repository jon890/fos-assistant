package com.bifos.assistant.connector.approval.application;

import com.bifos.assistant.connector.financial.application.ConnectorExecutionSnapshot;
import com.bifos.assistant.connector.financial.application.FinancialDigest;
import com.bifos.assistant.connector.financial.application.FinancialOrderLimits;
import com.bifos.assistant.connector.approval.application.dto.FinancialActionSummary;
import com.bifos.assistant.connector.approval.application.dto.FinancialActionSummary.Normalization;
import com.bifos.assistant.connector.financial.application.dto.FinancialLimitStatus;
import com.bifos.assistant.connector.financial.application.dto.FinancialLimitValues;
import com.bifos.assistant.connector.action.domain.ConnectorAction;
import com.bifos.assistant.connector.action.domain.type.FinancialLimitKind;
import com.bifos.assistant.connector.connection.domain.type.ToolRisk;
import com.bifos.assistant.connector.action.infra.ConnectorActionExecutionRepository;
import com.bifos.assistant.connector.action.infra.ConnectorActionRepository;
import com.bifos.assistant.crypto.domain.TextCipher;
import com.bifos.assistant.shared.auth.CurrentUser;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 금융 승인 카드에 붙일 요약이다(ADR-20261013 / financial-order-opening). 주인의 위험도 {@code FINANCIAL} 줄만 다룬다.
 *
 * <p>저장한 snapshot 을 승인과 같은 검증으로 연다. 열지 못하면 승인 단추를 그리지 않도록 {@code available=false} 를 낸다. 외부를 부르지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConnectorFinancialSummaries {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final ConnectorActionRepository actions;
    private final ConnectorActionExecutionRepository contents;
    private final TextCipher cipher;
    private final FinancialDigest digest;
    private final FinancialOrderLimits limits;
    private final Clock clock;


    /** 줄 번호마다의 요약이다. 없거나 남의 줄이거나 금융 줄이 아니면 빠진다. */
    @Transactional(readOnly = true)
    public Map<UUID, FinancialActionSummary> summaries(CurrentUser user, List<UUID> actionIds) {
        var found = new LinkedHashMap<UUID, FinancialActionSummary>();
        // 상한과 오늘 쓴 금액은 사용자마다 한 번만 읽는다.
        var usage = new Usage(user.id());
        for (UUID actionId : actionIds) {
            owned(user, actionId).map(action -> summary(action, usage)).ifPresent(summary -> found.put(actionId, summary));
        }
        return found;
    }


    /** 그 줄의 요약이다. 없거나 남의 줄이거나 금융 줄이 아니면 빈 값이다. */
    @Transactional(readOnly = true)
    public Optional<FinancialActionSummary> summaryOf(CurrentUser user, UUID actionId) {
        return owned(user, actionId).map(action -> summary(action, new Usage(user.id())));
    }


    private Optional<ConnectorAction> owned(CurrentUser user, UUID actionId) {
        return actions.findByPublicId(actionId)
            .filter(action -> user.id().equals(action.userId()) && action.risk() == ToolRisk.FINANCIAL);
    }


    private FinancialActionSummary summary(ConnectorAction action, Usage usage) {
        if (action.bodyErasedAt() != null) {
            return FinancialActionSummary.unavailable();
        }
        try {
            var row = contents.findById(action.id()).orElse(null);
            if (row == null) {
                return FinancialActionSummary.unavailable();
            }
            var snapshot = ConnectorExecutionSnapshot.open(action, row, cipher, digest);
            JsonNode scope = JSON.readTree(snapshot.scopeJson());
            if (!ConnectorExecutionSnapshot.ORDER.equals(snapshot.kind()) || !scope.isObject() || scope.size() != 1) {
                return FinancialActionSummary.unavailable();
            }
            JsonNode shown = JSON.readTree(snapshot.summaryJson());
            var changes = new ArrayList<Normalization>();
            for (JsonNode change : shown.path("normalization")) {
                changes.add(new Normalization(text(change, "field"), text(change, "before"), text(change, "after")));
            }
            String currency = text(shown, "currency");
            var status = "CREATE".equals(text(shown, "operation")) ? usage.status(currency) : null;
            return new FinancialActionSummary(true, scope.properties().iterator().next().getValue().stringValue(),
                text(shown, "operation"), text(shown, "symbol"), text(shown, "market"), currency,
                text(shown, "side"), text(shown, "quantity"), text(shown, "orderAmount"), text(shown, "price"),
                text(shown, "orderType"), text(shown, "timeInForce"), text(shown, "orderId"), changes, status);
        } catch (RuntimeException ex) {
            // 예외 메시지에는 원문이 섞일 수 있어 종류만 남긴다.
            log.warn("금융 승인 요약을 열지 못했다 actionId={} kind={}", action.publicId(), ex.getClass().getSimpleName());
            return FinancialActionSummary.unavailable();
        }
    }


    /**
     * 한 사용자의 적용 상한과 오늘 쓴 금액을 처음 쓸 때 한 번 읽는다. 읽지 못하면 카드의 상한 표시만 비우고 요약은 그대로 낸다.
     * 판정은 승인할 때 서버가 다시 하므로 표시를 비워도 승인이 느슨해지지 않는다.
     */
    private final class Usage {
        private final Long userId;
        private FinancialLimitValues values;
        private Map<String, BigDecimal> spent;
        private boolean failed;


        Usage(Long userId) {
            this.userId = userId;
        }


        FinancialLimitStatus status(String currency) {
            if (!"KRW".equals(currency) && !"USD".equals(currency)) {
                return null;
            }
            if (values == null && !failed) {
                try {
                    values = limits.effective(userId);
                    spent = limits.spentToday(userId, null, clock.instant());
                } catch (RuntimeException ex) {
                    failed = true;
                    log.warn("금융 승인 카드의 상한을 읽지 못했다 kind={}", ex.getClass().getSimpleName());
                }
            }
            if (failed) {
                return null;
            }
            BigDecimal daily = values.value(FinancialLimitKind.dailyAmount(currency));
            BigDecimal used = spent.getOrDefault(currency, BigDecimal.ZERO);
            return new FinancialLimitStatus(currency, FinancialOrderLimits.plain(values.value(FinancialLimitKind.orderAmount(currency))),
                FinancialOrderLimits.plain(values.value(FinancialLimitKind.ORDER_QUANTITY)), FinancialOrderLimits.plain(daily),
                FinancialOrderLimits.plain(used), FinancialOrderLimits.plain(daily.subtract(used).max(BigDecimal.ZERO)));
        }
    }


    /** summary 의 칸을 글 그대로 옮긴다. 없거나 null 이면 null 이다. 검증을 지난 summary 라 그 밖의 칸은 문자열이다. */
    private static String text(JsonNode node, String name) {
        JsonNode value = node.get(name);
        return value == null || value.isNull() ? null : value.stringValue();
    }
}
