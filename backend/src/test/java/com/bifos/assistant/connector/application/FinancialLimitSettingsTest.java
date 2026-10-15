package com.bifos.assistant.connector.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.connector.application.model.FinancialLimitsView;
import com.bifos.assistant.connector.domain.type.FinancialLimitKind;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.config.LiveProperties;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.testsupport.OverridableLiveProperties;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 화면이 상한을 읽고 바꾸는 길이다. 올리기와 낮추기가 바로 반영되고 최대치를 넘지 못한다. */
@BackendIntegrationTest
class FinancialLimitSettingsTest {
    private static final Instant NOW = Instant.parse("2026-10-15T01:00:00Z");
    @Autowired
    FinancialLimitSettings settings;
    @Autowired
    LiveProperties<ConnectorFinancialProperties> financial;
    @Autowired
    AppUserRepository users;
    CurrentUser owner;
    CurrentUser stranger;


    @BeforeEach
    void setUp() {
        owner = user();
        stranger = user();
        OverridableLiveProperties.during(financial, new ConnectorFinancialProperties(List.of("7:" + owner.email().toUpperCase()),
            new BigDecimal("100000"), new BigDecimal("1000"), new BigDecimal("10"), new BigDecimal("300000"), new BigDecimal("1000"), ""));
    }


    @Test
    @DisplayName("상한을 낮추고 다시 올리면 바로 반영되고, null 이면 최대치로 돌아간다")
    void lowersAndRaisesImmediately() {
        var lowered = settings.set(owner, FinancialLimitKind.ORDER_AMOUNT_USD, new BigDecimal("300"), NOW);
        assertThat(item(lowered, "order_amount_usd").value()).isEqualTo("300");
        assertThat(item(lowered, "order_amount_usd").custom()).isTrue();
        var raised = settings.set(owner, FinancialLimitKind.ORDER_AMOUNT_USD, new BigDecimal("500.5"), NOW);
        assertThat(item(raised, "order_amount_usd").value()).isEqualTo("500.5");
        assertThat(item(settings.view(owner, NOW), "order_amount_usd").value()).isEqualTo("500.5");
        var reset = settings.set(owner, FinancialLimitKind.ORDER_AMOUNT_USD, null, NOW);
        assertThat(item(reset, "order_amount_usd").value()).isEqualTo("1000");
        assertThat(item(reset, "order_amount_usd").custom()).isFalse();
        assertThat(reset.available()).isTrue();
        assertThat(reset.remainingToday()).containsEntry("USD", "1000").containsEntry("KRW", "300000");
    }


    @Test
    @DisplayName("최대치보다 큰 값은 저장하지 않고 FINANCIAL_LIMIT_ABOVE_MAXIMUM 이다")
    void rejectsValueAboveMaximum() {
        settings.set(owner, FinancialLimitKind.DAILY_AMOUNT_USD, new BigDecimal("400"), NOW);
        assertThatThrownBy(() -> settings.set(owner, FinancialLimitKind.DAILY_AMOUNT_USD, new BigDecimal("1000.000001"), NOW))
            .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.FINANCIAL_LIMIT_ABOVE_MAXIMUM));
        assertThat(item(settings.view(owner, NOW), "daily_amount_usd").value()).isEqualTo("400");
        assertThatThrownBy(() -> settings.set(owner, FinancialLimitKind.DAILY_AMOUNT_USD, new BigDecimal("-1"), NOW))
            .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }


    @Test
    @DisplayName("허용 목록에 계좌가 없는 사용자는 available 이 거짓이고 바꾸지 못한다")
    void strangerCannotChange() {
        assertThat(settings.view(stranger, NOW).available()).isFalse();
        assertThatThrownBy(() -> settings.set(stranger, FinancialLimitKind.ORDER_QUANTITY, BigDecimal.ONE, NOW))
            .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.FORBIDDEN));
    }


    private static FinancialLimitsView.Item item(FinancialLimitsView view, String name) {
        return view.limits().stream().filter(item -> item.name().equals(name)).findFirst().orElseThrow();
    }


    private CurrentUser user() {
        var saved = users.saveAndFlush(AppUser.of(UUID.randomUUID() + "@example.test", "상한 검사", 1L, UserRole.MEMBER, NOW));
        return new CurrentUser(saved.id(), saved.email(), saved.displayName(), 1L, UserRole.MEMBER);
    }
}
