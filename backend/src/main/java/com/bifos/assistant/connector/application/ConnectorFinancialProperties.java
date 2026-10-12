package com.bifos.assistant.connector.application;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * 금융 실행의 운영 관문이다. 기본값은 모두 닫힌 쪽이다.
 *
 * <p>값은 기동 때 한 번 읽는다. 바꾸려면 운영 값을 고치고 재기동한다. 따로 켜고 끄는 설정은 두지 않는다.
 * 허용 목록이 비었거나 상한이 0 이거나 key 파일이 없으면 닫혀 있다.
 *
 * @param allowedAccounts 주문할 수 있는 계좌 식별자. 실행 scope 의 값이 이 목록에 있어야 한다. 비면 아무 계좌도 주문하지 못한다
 * @param maxOrderAmountKrw 원화 주문 한 건의 금액 상한. 0 이면 원화 주문을 받지 않는다
 * @param maxOrderAmountUsd 달러 주문 한 건의 금액 상한. 0 이면 달러 주문을 받지 않는다
 * @param maxOrderQuantity 주문 한 건의 수량 상한. 0 이면 수량 주문을 받지 않는다
 * @param maxDailyAmountKrw 한 사용자가 KST 하루에 승인한 원화 주문 금액 합의 상한
 * @param maxDailyAmountUsd 한 사용자가 KST 하루에 승인한 달러 주문 금액 합의 상한
 * @param digestKeyFile 금융 줄의 DB 해시 칸을 keyed hash 하는 key 파일. 없거나 읽지 못하면 금융 관문이 닫힌다
 */
@Validated
@ConfigurationProperties(prefix = "assistant.connector.financial")
public record ConnectorFinancialProperties(
    @DefaultValue List<String> allowedAccounts,
    @DefaultValue("0") BigDecimal maxOrderAmountKrw,
    @DefaultValue("0") BigDecimal maxOrderAmountUsd,
    @DefaultValue("0") BigDecimal maxOrderQuantity,
    @DefaultValue("0") BigDecimal maxDailyAmountKrw,
    @DefaultValue("0") BigDecimal maxDailyAmountUsd,
    @DefaultValue("") String digestKeyFile) {

    /** 음수 상한과 빈 계좌 식별자는 기동을 멈춘다. 잘못 넣은 값이 「상한 없음」 으로 읽히지 않게 한다. */
    public ConnectorFinancialProperties {
        allowedAccounts = allowedAccounts == null ? List.of() : List.copyOf(allowedAccounts);
        for (String account : allowedAccounts) {
            if (account == null || account.isBlank()) {
                throw new IllegalStateException("assistant.connector.financial.allowed-accounts must not contain blanks");
            }
        }
        for (BigDecimal limit : new BigDecimal[] {
            maxOrderAmountKrw, maxOrderAmountUsd, maxOrderQuantity, maxDailyAmountKrw, maxDailyAmountUsd}) {
            if (limit == null || limit.signum() < 0) {
                throw new IllegalStateException("assistant.connector.financial limits must be zero or positive: " + limit);
            }
        }
    }
}
