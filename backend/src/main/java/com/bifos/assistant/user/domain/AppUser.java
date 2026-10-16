package com.bifos.assistant.user.domain;

import com.bifos.assistant.shared.domain.AuditedEntity;
import com.bifos.assistant.shared.domain.type.UserRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.experimental.Accessors;

@Entity
@Table(name = "app_user")
@Accessors(fluent = true)
public class AppUser extends AuditedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Getter
    private Long id;

    @Column(name = "email", nullable = false, unique = true, length = 320)
    @Getter
    private String email;

    @Column(name = "display_name", nullable = false, length = 100)
    @Getter
    private String displayName;

    @Column(name = "group_id", nullable = false)
    @Getter
    private Long groupId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    @Getter
    private UserRole role;


    @Column(name = "model_default_tier", length = 16)
    @Getter
    @Accessors(fluent = true)
    private String modelDefaultTier;

    // 금융 주문 상한이다(ADR-20261015 / financial-user-limits). 컬럼의 뜻은 data-schema.md 의 「app_user」 가 갖는다.
    // 일반 저장이 먼저 읽어 둔 옛 값으로 덮지 않도록 넣기와 고치기에서 빼고, 읽기와 쓰기는 FinancialOrderLimitRepository 의 전용 쿼리로만 한다.
    // 그래서 getter 를 두지 않는다. 엔티티의 값은 상한을 바꾼 뒤에도 옛 값일 수 있다.
    @Column(name = "limit_order_amount_krw", precision = 20, scale = 6, insertable = false, updatable = false)
    private BigDecimal limitOrderAmountKrw;

    @Column(name = "limit_order_amount_usd", precision = 20, scale = 6, insertable = false, updatable = false)
    private BigDecimal limitOrderAmountUsd;

    @Column(name = "limit_order_quantity", precision = 20, scale = 6, insertable = false, updatable = false)
    private BigDecimal limitOrderQuantity;

    @Column(name = "limit_daily_amount_krw", precision = 20, scale = 6, insertable = false, updatable = false)
    private BigDecimal limitDailyAmountKrw;

    @Column(name = "limit_daily_amount_usd", precision = 20, scale = 6, insertable = false, updatable = false)
    private BigDecimal limitDailyAmountUsd;

    @Column(name = "limit_updated_at", insertable = false, updatable = false)
    private Instant limitUpdatedAt;


    protected AppUser() {}


    private AppUser(String email, String displayName, Long groupId, UserRole role) {
        this.email = email;
        this.displayName = displayName;
        this.groupId = groupId;
        this.role = role;
    }


    public static AppUser of(String email, String displayName, Long groupId, UserRole role) {
        return new AppUser(email, displayName, groupId, role);
    }


    public boolean isAdmin() {
        return role == UserRole.ADMIN;
    }


    public void rename(String displayName) {
        this.displayName = displayName;
    }
}
