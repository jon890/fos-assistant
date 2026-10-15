package com.bifos.assistant.connector.application;

import com.bifos.assistant.connector.application.model.FinancialLimitValues;
import com.bifos.assistant.connector.application.model.FinancialLimitsView;
import com.bifos.assistant.connector.domain.type.FinancialLimitKind;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.config.LiveProperties;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 계좌 주인이 자기 주문 상한을 읽고 바꾸는 곳이다(ADR-20261015 / financial-user-limits).
 *
 * <p>바꾸는 쪽은 금융 준비와 승인처럼 먼저 사용자 행을 잠근다. 그래서 주문 판정과 상한 변경이 차례로 일어난다.
 * 화면의 변경은 계좌 주인의 직접 조작이라 승인 카드를 만들지 않는다. 에이전트는 이 경로를 부를 수 없다.
 */
@Component
@RequiredArgsConstructor
public class FinancialLimitSettings {
    /** 금액과 수량은 소수 6자리까지다. 저장 컬럼의 scale 과 같다. */
    static final int SCALE = 6;
    private final LiveProperties<ConnectorFinancialProperties> properties;
    private final FinancialOrderLimits limits;
    private final AppUserRepository users;


    /** 그 사용자의 상한과 오늘 쓴 금액이다. 허용 목록에 계좌가 없으면 {@code available} 이 거짓이다. */
    @Transactional(readOnly = true)
    public FinancialLimitsView view(CurrentUser user, Instant now) {
        boolean available = hasAccount(user.email());
        FinancialLimitValues values = limits.effective(user.id());
        var items = new ArrayList<FinancialLimitsView.Item>();
        for (FinancialLimitKind kind : FinancialLimitKind.values()) {
            items.add(new FinancialLimitsView.Item(kind.apiName(), kind.currency(), kind.label(),
                FinancialOrderLimits.plain(values.value(kind)), FinancialOrderLimits.plain(values.maximum(kind)), values.custom(kind)));
        }
        Map<String, BigDecimal> spent = available ? limits.spentToday(user.id(), null, now) : Map.of();
        var used = new LinkedHashMap<String, String>();
        var remaining = new LinkedHashMap<String, String>();
        for (String currency : new String[] {"KRW", "USD"}) {
            BigDecimal amount = spent.getOrDefault(currency, BigDecimal.ZERO);
            used.put(currency, FinancialOrderLimits.plain(amount));
            BigDecimal daily = values.value(FinancialLimitKind.dailyAmount(currency));
            remaining.put(currency, FinancialOrderLimits.plain(daily.subtract(amount).max(BigDecimal.ZERO)));
        }
        return new FinancialLimitsView(available, items, used, remaining);
    }


    /**
     * 화면에서 상한 하나를 바꾼다. 올리기도 바로 반영한다. null 은 사용자 값을 지워 최대치를 따르게 한다.
     *
     * @throws ApiException 허용 목록에 계좌가 없으면 {@code FORBIDDEN}, 값 모양이 틀리면 {@code VALIDATION_FAILED},
     * 최대치보다 크면 {@code FINANCIAL_LIMIT_ABOVE_MAXIMUM}
     */
    @Transactional
    public FinancialLimitsView set(CurrentUser user, FinancialLimitKind kind, BigDecimal value, Instant now) {
        if (!hasAccount(user.email())) {
            throw new ApiException(ErrorCode.FORBIDDEN, "no financial account");
        }
        users.findByIdForUpdate(user.id()).orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN, "no user"));
        if (value != null) {
            requireShape(value);
            requireWithinMaximum(kind, value, limits.effective(user.id()));
        }
        limits.store(user.id(), kind, value, now);
        return view(user, now);
    }


    /** 허용 목록에 그 메일의 계좌가 있는가. 메일은 대소문자를 가리지 않는다. */
    boolean hasAccount(String email) {
        return email != null && properties.current().allowedAccounts().stream()
            .anyMatch(entry -> entry.substring(entry.indexOf(':') + 1).equalsIgnoreCase(email));
    }


    /** 0 이상이고 소수 6자리까지인 값만 받는다. */
    static void requireShape(BigDecimal value) {
        if (value.signum() < 0 || value.stripTrailingZeros().scale() > SCALE || value.precision() - value.scale() > 14) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "financial limit must be a non-negative decimal");
        }
    }


    /** 운영 최대치를 넘는 값은 받지 않는다. */
    static void requireWithinMaximum(FinancialLimitKind kind, BigDecimal value, FinancialLimitValues values) {
        if (value.compareTo(values.maximum(kind)) > 0) {
            throw new ApiException(ErrorCode.FINANCIAL_LIMIT_ABOVE_MAXIMUM, "financial limit above maximum");
        }
    }
}
