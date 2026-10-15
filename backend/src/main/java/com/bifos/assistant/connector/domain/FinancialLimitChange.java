package com.bifos.assistant.connector.domain;

import com.bifos.assistant.connector.domain.type.FinancialLimitKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

/**
 * 대화로 올리기를 요청한 주문 상한 변경이다(ADR-20261015 / financial-user-limits). 승인 줄 하나에 한 줄이다.
 *
 * <p>컬럼의 뜻은 {@code backend/docs/data-schema.md} 의 「financial_limit_change」 가 갖는다. 승인하기 전에는 상한을 바꾸지 않는다.
 */
@Entity
@Table(name = "financial_limit_change")
@Getter
@Accessors(fluent = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FinancialLimitChange {

    @Id
    @Column(name = "action_id", nullable = false)
    private Long actionId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "limit_kind", nullable = false, length = 32)
    private FinancialLimitKind limitKind;

    @Column(name = "requested_value", nullable = false, precision = 20, scale = 6)
    private BigDecimal requestedValue;

    /** 요청할 때 적용하던 상한이다. 카드가 바뀌기 전 값을 보인다. */
    @Column(name = "previous_value", nullable = false, precision = 20, scale = 6)
    private BigDecimal previousValue;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;


    public static FinancialLimitChange of(Long actionId, Long userId, FinancialLimitKind kind, BigDecimal requested,
        BigDecimal previous, Instant now) {
        FinancialLimitChange change = new FinancialLimitChange();
        change.actionId = actionId;
        change.userId = userId;
        change.limitKind = kind;
        change.requestedValue = requested;
        change.previousValue = previous;
        change.createdAt = now;
        return change;
    }
}
