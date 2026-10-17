package com.bifos.assistant.connector.financial.config;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * 금융 실행의 운영 관문이다. 기본값은 모두 닫힌 쪽이다.
 *
 * <p>값은 기동 때 한 번 읽는다. 바꾸려면 운영 값을 고치고 재기동한다. 따로 켜고 끄는 설정은 두지 않는다.
 * 허용 목록이 비었거나 상한이 0 이거나 key 파일이 없으면 닫혀 있다.
 *
 * @param allowedAccounts 주문할 수 있는 계좌와 그 계좌를 쓰는 사용자. 항목은 {@code <계좌 순번>:<사용자 메일>} 이고 한 계좌는 한 번만 적는다.
 * 실행 scope 의 값이 목록의 계좌와 글자 그대로 같고 그 항목의 메일이 줄 주인의 메일이어야 한다. 메일은 대소문자를 가리지 않는다. 비면 아무 계좌도 주문하지 못한다
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


    private static final String ACCOUNT = "[0-9]{1,19}";
    private static final String EMAIL = "[^\\s:@]+@[^\\s:@]+";

    /**
     * 음수 상한, 형식이 틀린 허용 목록 항목, 두 번 나온 계좌는 기동을 멈춘다. 잘못 넣은 값이 「상한 없음」 이나 다른 사용자의 계좌로 읽히지 않게 한다.
     * 값 원문을 메시지에 넣으면 메일과 계좌 순번이 기동 로그에 남으므로 넣지 않는다.
     */
    public ConnectorFinancialProperties {
        allowedAccounts = allowedAccounts == null ? List.of() : List.copyOf(allowedAccounts);
        var accounts = new HashSet<String>();
        for (String entry : allowedAccounts) {
            int separator = entry == null ? -1 : entry.indexOf(':');
            if (separator < 0 || !entry.substring(0, separator).matches(ACCOUNT) || !entry.substring(separator + 1).matches(EMAIL)) {
                throw new IllegalStateException("assistant.connector.financial.allowed-accounts entries must be <account>:<email>");
            }
            if (!accounts.add(entry.substring(0, separator))) {
                throw new IllegalStateException("assistant.connector.financial.allowed-accounts must list each account once");
            }
        }
        for (BigDecimal limit : new BigDecimal[] {
            maxOrderAmountKrw, maxOrderAmountUsd, maxOrderQuantity, maxDailyAmountKrw, maxDailyAmountUsd}) {
            if (limit == null || limit.signum() < 0) {
                throw new IllegalStateException("assistant.connector.financial limits must be zero or positive: " + limit);
            }
        }
    }


    /**
     * 그 계좌를 쓰는 사용자의 메일이다. 목록에 없으면 빈 값이다.
     *
     * <p>계좌 순번은 글자 그대로 견준다. 앞의 0 만 다른 표기도 다른 계좌다. 실행 scope 의 값을 바꾸지 않고 적은 그대로 맞춘다.
     */
    public Optional<String> ownerOf(String account) {
        if (account == null) {
            return Optional.empty();
        }
        return allowedAccounts.stream()
            .filter(entry -> entry.substring(0, entry.indexOf(':')).equals(account))
            .map(entry -> entry.substring(entry.indexOf(':') + 1))
            .findFirst();
    }
}
