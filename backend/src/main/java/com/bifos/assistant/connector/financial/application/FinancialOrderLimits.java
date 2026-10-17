package com.bifos.assistant.connector.financial.application;

import com.bifos.assistant.connector.financial.config.ConnectorFinancialProperties;
import com.bifos.assistant.connector.financial.application.dto.FinancialLimitValues;
import com.bifos.assistant.connector.financial.domain.FinancialOrderLimit;
import com.bifos.assistant.connector.action.domain.type.ActionStatus;
import com.bifos.assistant.connector.action.domain.type.FinancialLimitKind;
import com.bifos.assistant.connector.connection.domain.type.ToolRisk;
import com.bifos.assistant.connector.action.infra.ConnectorActionExecutionRepository;
import com.bifos.assistant.connector.action.infra.ConnectorActionRepository;
import com.bifos.assistant.connector.financial.infra.FinancialOrderLimitRepository;
import com.bifos.assistant.crypto.domain.TextCipher;
import com.bifos.assistant.shared.config.LiveProperties;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * 한 사용자에게 지금 적용하는 주문 상한과 오늘 쓴 금액을 계산하는 한 곳이다(ADR-20261015 / financial-user-limits).
 *
 * <p>적용 상한은 사용자가 정한 값과 운영 최대치 가운데 작은 것이다. 정하지 않은 종류는 최대치를 따른다.
 */
@Component
@RequiredArgsConstructor
public class FinancialOrderLimits {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    // 결과를 모르는 UNKNOWN 도 센다. 빼면 응답을 잃은 주문만큼 하루 상한을 넘을 수 있다.
    private static final Set<ActionStatus> SPENT = Set.of(ActionStatus.EXECUTING, ActionStatus.SUCCEEDED, ActionStatus.UNKNOWN);
    private final LiveProperties<ConnectorFinancialProperties> properties;
    private final FinancialOrderLimitRepository limits;
    private final ConnectorActionRepository actions;
    private final ConnectorActionExecutionRepository contents;
    private final TextCipher cipher;
    private final FinancialDigest digest;


    /** 운영 최대치다. 사용자 값이 없을 때 적용하는 값과 같다. */
    public static FinancialLimitValues maximumsOf(ConnectorFinancialProperties settings) {
        var maximums = new EnumMap<FinancialLimitKind, BigDecimal>(FinancialLimitKind.class);
        maximums.put(FinancialLimitKind.ORDER_AMOUNT_KRW, settings.maxOrderAmountKrw());
        maximums.put(FinancialLimitKind.ORDER_AMOUNT_USD, settings.maxOrderAmountUsd());
        maximums.put(FinancialLimitKind.ORDER_QUANTITY, settings.maxOrderQuantity());
        maximums.put(FinancialLimitKind.DAILY_AMOUNT_KRW, settings.maxDailyAmountKrw());
        maximums.put(FinancialLimitKind.DAILY_AMOUNT_USD, settings.maxDailyAmountUsd());
        return new FinancialLimitValues(maximums, maximums, Set.of());
    }


    /** 그 사용자에게 지금 적용하는 상한이다. 운영 최대치가 사용자 값보다 작으면 최대치가 이긴다. */
    public FinancialLimitValues effective(Long userId) {
        var maximums = maximumsOf(properties.current()).maximums();
        var stored = userId == null ? FinancialOrderLimit.NONE : limits.findByUserId(userId).orElse(FinancialOrderLimit.NONE);
        var values = new EnumMap<FinancialLimitKind, BigDecimal>(FinancialLimitKind.class);
        var custom = EnumSet.noneOf(FinancialLimitKind.class);
        for (FinancialLimitKind kind : FinancialLimitKind.values()) {
            BigDecimal own = stored.value(kind);
            BigDecimal maximum = maximums.get(kind);
            values.put(kind, own == null ? maximum : own.min(maximum));
            if (own != null) {
                custom.add(kind);
            }
        }
        return new FinancialLimitValues(values, maximums, custom);
    }


    /**
     * 그 사용자가 KST 오늘 승인한 주문의 통화별 금액 합이다. 승인으로 넘어간 EXECUTING, SUCCEEDED, UNKNOWN 을 센다.
     * 실행 원문을 열지 못한 줄이 있으면 합계를 내지 않고 닫는다.
     *
     * @param ownActionId 합계에서 뺄 자기 승인 줄. 없으면 null
     */
    public Map<String, BigDecimal> spentToday(Long userId, Long ownActionId, Instant now) {
        var spent = new HashMap<String, BigDecimal>();
        Instant from = now.atZone(KST).toLocalDate().atStartOfDay(KST).toInstant();
        for (var action : actions.findByUserIdAndRiskAndDecidedAtGreaterThanEqual(userId, ToolRisk.FINANCIAL, from)) {
            if (Objects.equals(action.id(), ownActionId) || !SPENT.contains(action.status())) {
                continue;
            }
            var row = contents.findById(action.id()).orElseThrow(ConnectorFinancialGate::closed);
            JsonNode spentArgs = ConnectorExecutionSnapshot.openExecutionArgs(action, row, cipher, digest);
            var amount = ConnectorFinancialGate.amount(spentArgs);
            if (amount.isPresent()) {
                spent.merge(spentArgs.get("currency").stringValue(), amount.get(), BigDecimal::add);
            }
        }
        return spent;
    }


    /** 금액과 수량을 소수점 뒤 0 을 뺀 십진 글로 바꾼다. */
    public static String plain(BigDecimal value) {
        return value.signum() == 0 ? "0" : value.stripTrailingZeros().toPlainString();
    }


    /**
     * 사용자 값을 바꾼다. 호출자는 같은 트랜잭션에서 사용자 행을 먼저 잠그고 값을 검사한다.
     * 잠근 뒤 다시 읽으므로 다른 종류의 값을 옛 값으로 덮지 않는다.
     */
    public void store(Long userId, FinancialLimitKind kind, BigDecimal value, Instant now) {
        FinancialOrderLimit row = limits.findByUserId(userId).orElseThrow(() -> new IllegalStateException("no user"))
            .with(kind, value);
        limits.store(userId, row.orderAmountKrw(), row.orderAmountUsd(), row.orderQuantity(), row.dailyAmountKrw(),
            row.dailyAmountUsd(), now, now.truncatedTo(ChronoUnit.MICROS));
    }
}
