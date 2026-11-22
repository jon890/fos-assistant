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
import org.hibernate.annotations.ColumnDefault;

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

    // 웹 푸시 기기 목록 JSON 이다(ADR-20261120 / web-push). 주문 상한과 같은 까닭으로 넣기와 고치기에서 빼고 getter 를 두지 않는다.
    // 읽기와 쓰기는 PushDeviceRepository 의 전용 쿼리로만 한다. 검사의 create-drop 스키마가 같은 칸을 갖도록 매핑한다.
    @Column(name = "push_devices", columnDefinition = "TEXT", insertable = false, updatable = false)
    private String pushDevices;

    // 옛 「결과물 자동 보관」 설정이다(#530). 스위치를 없애 더 읽거나 쓰지 않는다(스펙 workspace REQ16).
    // 칸은 마이그레이션에 남아 있어 검사의 create-drop 스키마가 같은 칸을 갖도록 매핑만 둔다. 넣기와 고치기에서 뺀다.
    @ColumnDefault("true")
    @Column(name = "artifact_auto_vault", insertable = false, updatable = false)
    private Boolean artifactAutoVault;

    /**
     * 평가 전용 사용자인가(docs/features/agent-quality/design.md 의 「평가 전용 사용자」). 참이면 로그인하지 않고 일반 화면과 집계에서
     * 빠진다. 먼저 읽어 둔 사용자를 저장해도 덮지 않도록 넣기와 고치기에서 빼고 {@code AppUserRepository.markEvaluation} 으로만 바꾼다.
     */
    @Getter
    @ColumnDefault("false")
    @Column(name = "evaluation", nullable = false, insertable = false, updatable = false)
    private boolean evaluation;


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
