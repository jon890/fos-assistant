package com.bifos.assistant.connector.domain;

import com.bifos.assistant.connector.domain.type.FinancialLimitKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

/**
 * 계좌 주인이 운영 최대치 안에서 정한 주문 상한이다(ADR-20261015 / financial-user-limits).
 *
 * <p>컬럼의 뜻은 {@code backend/docs/data-schema.md} 의 「financial_order_limit」 가 갖는다. 빈 컬럼은 운영 최대치를 따른다.
 * 쓰는 쪽은 같은 트랜잭션에서 그 사용자의 {@code app_user} 행을 먼저 잠근다.
 */
@Entity
@Table(name = "financial_order_limit")
@Getter
@Accessors(fluent = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FinancialOrderLimit {

    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "order_amount_krw", precision = 20, scale = 6)
    private BigDecimal orderAmountKrw;

    @Column(name = "order_amount_usd", precision = 20, scale = 6)
    private BigDecimal orderAmountUsd;

    @Column(name = "order_quantity", precision = 20, scale = 6)
    private BigDecimal orderQuantity;

    @Column(name = "daily_amount_krw", precision = 20, scale = 6)
    private BigDecimal dailyAmountKrw;

    @Column(name = "daily_amount_usd", precision = 20, scale = 6)
    private BigDecimal dailyAmountUsd;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;


    public static FinancialOrderLimit of(Long userId, Instant now) {
        FinancialOrderLimit limit = new FinancialOrderLimit();
        limit.userId = userId;
        limit.updatedAt = now;
        return limit;
    }


    /** 사용자가 정한 값이다. 정하지 않았으면 null 이다. */
    public BigDecimal value(FinancialLimitKind kind) {
        return switch (kind) {
            case ORDER_AMOUNT_KRW -> orderAmountKrw;
            case ORDER_AMOUNT_USD -> orderAmountUsd;
            case ORDER_QUANTITY -> orderQuantity;
            case DAILY_AMOUNT_KRW -> dailyAmountKrw;
            case DAILY_AMOUNT_USD -> dailyAmountUsd;
        };
    }


    /** 사용자가 정한 값을 바꾼다. null 이면 운영 최대치를 따르게 한다. */
    public void set(FinancialLimitKind kind, BigDecimal value, Instant now) {
        switch (kind) {
            case ORDER_AMOUNT_KRW -> orderAmountKrw = value;
            case ORDER_AMOUNT_USD -> orderAmountUsd = value;
            case ORDER_QUANTITY -> orderQuantity = value;
            case DAILY_AMOUNT_KRW -> dailyAmountKrw = value;
            case DAILY_AMOUNT_USD -> dailyAmountUsd = value;
        }
        updatedAt = now;
    }
}
