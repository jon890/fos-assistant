package com.bifos.assistant.connector.application;

import com.bifos.assistant.connector.domain.type.ActionStatus;
import com.bifos.assistant.connector.domain.type.ToolRisk;
import com.bifos.assistant.connector.infra.ConnectorActionExecutionRepository;
import com.bifos.assistant.connector.infra.ConnectorActionRepository;
import com.bifos.assistant.crypto.domain.TextCipher;
import com.bifos.assistant.shared.config.LiveProperties;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 준비, 승인, claim 이 함께 지나는 금융 실행의 운영 관문이다.
 *
 * <p>kill switch, 계좌 허용 목록, 한 건과 하루 상한을 본다. 모두 닫힌 쪽이 기본이며 거절은 금융 전송 없이 끝난다.
 * 첫 개방은 일반 주문의 생성과 취소뿐이다. 일반 주문 정정과 조건주문 생성·정정은 금액 상한을 계산하지 않고 거절한다.
 */
@Component
@RequiredArgsConstructor
public class ConnectorFinancialGate {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    // 결과를 모르는 UNKNOWN 도 센다. 빼면 응답을 잃은 주문만큼 하루 상한을 넘을 수 있다.
    private static final Set<ActionStatus> SPENT = Set.of(ActionStatus.EXECUTING, ActionStatus.SUCCEEDED, ActionStatus.UNKNOWN);
    private final LiveProperties<ConnectorFinancialProperties> properties;
    private final ConnectorActionRepository actions;
    private final ConnectorActionExecutionRepository contents;
    private final TextCipher cipher;
    private final FinancialDigest digest;


    /** kill switch 와 함께 keyed hash key 를 본다. key 를 쓰는 단계 없이 켜면 열리지 않는다. */
    public void requireOpen() {
        require(properties.current().enabled() && digest.available());
    }


    /** 실행 scope 의 값이 모두 허용 목록에 있어야 한다. 금융 scope 는 계좌 하나만 담는다. */
    public void requireAccounts(Collection<String> scopeValues) {
        require(!scopeValues.isEmpty() && properties.current().allowedAccounts().containsAll(scopeValues));
    }


    /** 저장한 scope 원문의 값을 허용 목록과 대조한다. */
    public void requireScope(String scopeJson) {
        JsonNode scope = JSON.readTree(scopeJson);
        require(scope.isObject());
        var values = new ArrayList<String>();
        scope.properties().forEach(entry -> {
            require(entry.getValue().isString());
            values.add(entry.getValue().stringValue());
        });
        requireAccounts(values);
    }


    /** 호출자는 이 조회 전에 소유 사용자 행을 잠근다. 자기 승인 줄은 하루 합계에서 뺀다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireWithinLimits(Long userId, Long ownActionId, String executionArgsJson, Instant now) {
        var limits = properties.current();
        JsonNode args = JSON.readTree(executionArgsJson);
        Optional<BigDecimal> order = amount(args, limits);
        if (order.isEmpty()) {
            return;
        }
        String currency = args.get("currency").stringValue();
        BigDecimal total = order.get();
        Instant from = now.atZone(KST).toLocalDate().atStartOfDay(KST).toInstant();
        for (var action : actions.findByUserIdAndRiskAndDecidedAtGreaterThanEqual(userId, ToolRisk.FINANCIAL, from)) {
            if (Objects.equals(action.id(), ownActionId) || !SPENT.contains(action.status())) {
                continue;
            }
            var row = contents.findById(action.id()).orElseThrow(ConnectorFinancialGate::closed);
            JsonNode spentArgs = ConnectorExecutionSnapshot.openExecutionArgs(action, row, cipher);
            var spent = amount(spentArgs, null);
            if (spent.isPresent() && currency.equals(spentArgs.get("currency").stringValue())) {
                total = total.add(spent.get());
            }
        }
        require(total.compareTo("KRW".equals(currency) ? limits.maxDailyAmountKrw() : limits.maxDailyAmountUsd()) <= 0);
    }


    /**
     * 실행 원문에서 새로 쓰는 금액을 읽는다. 취소는 빈 값이다. 값이 있으면 원문의 currency 는 KRW 나 USD 다.
     *
     * @param limits null 이 아니면 한 건 상한도 함께 본다
     */
    static Optional<BigDecimal> amount(JsonNode args, ConnectorFinancialProperties limits) {
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
            BigDecimal quantity = decimal(args.get("quantity"));
            if (limits != null) {
                require(quantity.compareTo(limits.maxOrderQuantity()) <= 0);
            }
            value = quantity.multiply(decimal(args.get("price")));
        }
        if (limits != null) {
            require(value.compareTo("KRW".equals(currency) ? limits.maxOrderAmountKrw() : limits.maxOrderAmountUsd()) <= 0);
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
