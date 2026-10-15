package com.bifos.assistant.connector.application;

import com.bifos.assistant.connector.domain.type.ActionStatus;
import com.bifos.assistant.connector.domain.type.FinancialLimitKind;
import com.bifos.assistant.connector.infra.ConnectorActionExecutionRepository;
import com.bifos.assistant.shared.config.LiveProperties;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 준비, 승인, claim 이 함께 지나는 금융 실행의 운영 관문이다.
 *
 * <p>keyed hash key, 계좌 허용 목록, 한 건과 하루 상한을 본다. 상한은 {@link FinancialOrderLimits} 가 정한 적용 상한이다. 모두 닫힌 쪽이 기본이며 거절은 금융 전송 없이 끝난다.
 * 첫 개방은 일반 주문의 생성과 취소뿐이다. 일반 주문 정정과 조건주문 생성·정정은 금액 상한을 계산하지 않고 거절한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConnectorFinancialGate {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<ActionStatus> OPEN = Set.of(ActionStatus.PENDING, ActionStatus.EXECUTING, ActionStatus.UNKNOWN);
    private final LiveProperties<ConnectorFinancialProperties> properties;
    private final ConnectorActionExecutionRepository contents;
    private final FinancialDigest digest;
    private final AppUserRepository users;
    private final FinancialOrderLimits limits;


    /**
     * 기동 때 관문 상태를 값 없이 한 줄 남긴다. 거절은 어느 검사에 걸렸는지 밝히지 않으므로 소유자가 무엇을 채울지 여기서 본다.
     * key 도 이때 처음 읽어 재기동 전까지 쓴다.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void logState() {
        var current = properties.current();
        log.info("financial gate: digestKey={}, allowedAccounts={}, zeroLimits={}",
            digest.available() ? "readable" : "missing", current.allowedAccounts().size(), zeroLimits(current));
    }


    /** 0 이라 그 주문을 모두 거절하는 상한의 설정 이름이다. */
    static List<String> zeroLimits(ConnectorFinancialProperties limits) {
        var named = new LinkedHashMap<String, BigDecimal>();
        named.put("max-order-amount-krw", limits.maxOrderAmountKrw());
        named.put("max-order-amount-usd", limits.maxOrderAmountUsd());
        named.put("max-order-quantity", limits.maxOrderQuantity());
        named.put("max-daily-amount-krw", limits.maxDailyAmountKrw());
        named.put("max-daily-amount-usd", limits.maxDailyAmountUsd());
        return named.entrySet().stream().filter(entry -> entry.getValue().signum() == 0).map(Map.Entry::getKey).toList();
    }


    /**
     * keyed hash key 를 읽을 수 있고, 지금 protocol 과 지금 key 로 만들지 않은 열린 줄이 없어야 열린다.
     * key 파일이 운영에서 금융을 여는 마지막 잠금이다. key 를 바꾸면 이전 key 의 PENDING, EXECUTING, UNKNOWN 이 남은 동안 닫힌다.
     */
    public void requireOpen() {
        require(digest.available()
            && !contents.existsOpenOutside(ConnectorExecutionSnapshot.PROTOCOL, digest.currentPrefix(), OPEN));
    }


    /**
     * 실행 scope 의 값이 모두 허용 목록의 계좌이고 그 항목의 메일이 그 사용자의 메일이어야 한다. 금융 scope 는 계좌 하나만 담는다.
     * 한 계좌의 주문이 한 사용자의 하루 합계에만 쌓이게 한다. 사용자가 없으면 닫는다.
     */
    public void requireAccounts(Long userId, Collection<String> scopeValues) {
        var limits = properties.current();
        String email = userId == null ? null : users.findById(userId).map(AppUser::email).orElse(null);
        require(email != null && !scopeValues.isEmpty() && scopeValues.stream().allMatch(value -> limits.ownerOf(value)
            .filter(owner -> owner.toLowerCase(Locale.ROOT).equals(email.toLowerCase(Locale.ROOT))).isPresent()));
    }


    /** 저장한 scope 원문의 값을 허용 목록과 그 사용자에 대조한다. */
    public void requireScope(Long userId, String scopeJson) {
        JsonNode scope = JSON.readTree(scopeJson);
        require(scope.isObject());
        var values = new ArrayList<String>();
        scope.properties().forEach(entry -> {
            require(entry.getValue().isString());
            values.add(entry.getValue().stringValue());
        });
        requireAccounts(userId, values);
    }


    /**
     * 그 사용자에게 지금 적용하는 상한으로 판정한다(ADR-20261015 / financial-user-limits). 호출자는 이 조회 전에 소유 사용자 행을 잠근다.
     * 상한을 바꾸는 쪽도 같은 행을 잠그므로 주문은 그 순간에 커밋된 상한으로 판정된다. 자기 승인 줄은 하루 합계에서 뺀다.
     *
     * <p>모양이 틀린 주문은 지금처럼 이유 없이 닫고, 상한에 걸린 주문만 {@link FinancialLimitExceeded} 로 걸린 상한을 밝힌다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireWithinLimits(Long userId, Long ownActionId, String executionArgsJson, Instant now) {
        JsonNode args = JSON.readTree(executionArgsJson);
        Optional<BigDecimal> order = amount(args);
        if (order.isEmpty()) {
            return;
        }
        var values = limits.effective(userId);
        String currency = args.get("currency").stringValue();
        BigDecimal spent = limits.spentToday(userId, ownActionId, now).getOrDefault(currency, BigDecimal.ZERO);
        FinancialLimitKind daily = FinancialLimitKind.dailyAmount(currency);
        BigDecimal remaining = values.value(daily).subtract(spent).max(BigDecimal.ZERO);
        if (!args.has("orderAmount")) {
            BigDecimal quantity = decimal(args.get("quantity"));
            if (quantity.compareTo(values.value(FinancialLimitKind.ORDER_QUANTITY)) > 0) {
                throw new FinancialLimitExceeded(FinancialLimitKind.ORDER_QUANTITY, values.value(FinancialLimitKind.ORDER_QUANTITY), null);
            }
        }
        FinancialLimitKind single = FinancialLimitKind.orderAmount(currency);
        if (order.get().compareTo(values.value(single)) > 0) {
            throw new FinancialLimitExceeded(single, values.value(single), remaining);
        }
        if (spent.add(order.get()).compareTo(values.value(daily)) > 0) {
            throw new FinancialLimitExceeded(daily, values.value(daily), remaining);
        }
    }


    /** 실행 원문에서 새로 쓰는 금액을 읽는다. 취소는 빈 값이다. 값이 있으면 원문의 currency 는 KRW 나 USD 다. 상한은 보지 않는다. */
    static Optional<BigDecimal> amount(JsonNode args) {
        require(args != null && args.isObject());
        boolean changesMoney = args.has("quantity") || args.has("orderAmount") || args.has("price");
        if (args.has("first") || args.has("conditionalOrderId")) {
            // 조건주문은 첫 개방 대상이 아니다. 취소만 금액 없이 지나고 생성과 정정은 닫는다.
            require(!args.has("first") && !changesMoney);
            return Optional.empty();
        }
        if (args.has("orderId")) {
            // 일반 주문 정정은 첫 개방 대상이 아니다.
            require(!changesMoney);
            return Optional.empty();
        }
        String currency = args.path("currency").asString("");
        require(Set.of("KRW", "USD").contains(currency));
        BigDecimal value;
        if (args.has("orderAmount")) {
            value = decimal(args.get("orderAmount"));
        } else {
            // 시장가 수량 주문은 금액을 미리 알 수 없어 닫는다.
            require(args.has("quantity") && args.has("price"));
            value = decimal(args.get("quantity")).multiply(decimal(args.get("price")));
        }
        return Optional.of(value);
    }


    private static BigDecimal decimal(JsonNode node) {
        require(node != null && node.isString());
        try {
            BigDecimal value = new BigDecimal(node.stringValue());
            require(value.signum() > 0);
            return value;
        } catch (NumberFormatException failure) {
            throw closed();
        }
    }


    private static void require(boolean condition) {
        if (!condition) {
            throw closed();
        }
    }


    static ApiException closed() {
        return new ApiException(ErrorCode.CONNECTOR_ACTION_NOT_PENDING, "financial execution closed");
    }
}
