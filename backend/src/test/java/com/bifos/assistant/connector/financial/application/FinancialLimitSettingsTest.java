package com.bifos.assistant.connector.financial.application;

import com.bifos.assistant.connector.financial.config.ConnectorFinancialProperties;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.connector.financial.application.dto.FinancialLimitsView;
import com.bifos.assistant.connector.action.domain.type.FinancialLimitKind;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 화면이 상한을 읽고 바꾸는 길이다. 올리기와 낮추기가 바로 반영되고 최대치를 넘지 못한다.
 * 상한은 {@code app_user} 컬럼에 있어 일반 사용자 저장과 주문 판정이 같은 행을 쓴다. 그 둘과 겹쳐도 상한이 지켜지는지도 본다.
 */
@BackendIntegrationTest
class FinancialLimitSettingsTest {
    private static final Instant NOW = Instant.parse("2026-10-15T01:00:00Z");
    @Autowired
    FinancialLimitSettings settings;
    @Autowired
    LiveProperties<ConnectorFinancialProperties> financial;
    @Autowired
    AppUserRepository users;
    @Autowired
    ConnectorFinancialGate gate;
    @Autowired
    PlatformTransactionManager manager;
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


    @Test
    @DisplayName("상한을 바꾸기 전에 읽어 둔 사용자를 저장해도 상한은 옛 값으로 돌아가지 않는다")
    void staleUserSaveKeepsLimits() {
        AppUser stale = users.findById(owner.id()).orElseThrow();
        settings.set(owner, FinancialLimitKind.ORDER_AMOUNT_USD, new BigDecimal("300"), NOW);
        stale.rename("이름 바꿈");
        users.saveAndFlush(stale);
        assertThat(users.findById(owner.id()).orElseThrow().displayName()).isEqualTo("이름 바꿈");
        assertThat(item(settings.view(owner, NOW), "order_amount_usd").value()).isEqualTo("300");
    }


    @Test
    @DisplayName("두 상한을 따로 바꿔도 앞서 바꾼 상한을 지우지 않는다")
    void storeKeepsOtherKinds() {
        settings.set(owner, FinancialLimitKind.ORDER_AMOUNT_USD, new BigDecimal("300"), NOW);
        var view = settings.set(owner, FinancialLimitKind.DAILY_AMOUNT_KRW, new BigDecimal("5000"), NOW);
        assertThat(item(view, "order_amount_usd").value()).isEqualTo("300");
        assertThat(item(view, "daily_amount_krw").value()).isEqualTo("5000");
    }


    @Test
    @DisplayName("상한 변경이 사용자 행을 잡은 동안 주문 판정은 기다렸다가 커밋된 새 상한으로 판정한다")
    void orderWaitsForLimitChange() throws Exception {
        var tx = new TransactionTemplate(manager);
        var changed = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> change = pool.submit(() -> tx.executeWithoutResult(status -> {
                settings.set(owner, FinancialLimitKind.ORDER_AMOUNT_USD, new BigDecimal("20"), NOW);
                changed.countDown();
                await(release);
            }));
            assertThat(changed.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> order = pool.submit(() -> tx.executeWithoutResult(status -> {
                users.findByIdForUpdate(owner.id()).orElseThrow();
                gate.requireWithinLimits(owner.id(), null, "{\"currency\":\"USD\",\"orderAmount\":\"50\"}", NOW);
            }));
            assertThatThrownBy(() -> order.get(500, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            change.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> order.get(10, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                .cause().isInstanceOfSatisfying(FinancialLimitExceeded.class, ex -> {
                    assertThat(ex.kind()).isEqualTo(FinancialLimitKind.ORDER_AMOUNT_USD);
                    assertThat(ex.limit()).isEqualByComparingTo("20");
                });
        } finally {
            release.countDown();
        }
    }


    @Test
    @DisplayName("주문 판정이 사용자 행을 잡은 동안 상한 변경은 기다렸다가 반영된다")
    void limitChangeWaitsForOrder() throws Exception {
        var tx = new TransactionTemplate(manager);
        var judged = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> order = pool.submit(() -> tx.executeWithoutResult(status -> {
                users.findByIdForUpdate(owner.id()).orElseThrow();
                gate.requireWithinLimits(owner.id(), null, "{\"currency\":\"USD\",\"orderAmount\":\"50\"}", NOW);
                judged.countDown();
                await(release);
            }));
            assertThat(judged.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> change = pool.submit(() -> settings.set(owner, FinancialLimitKind.ORDER_AMOUNT_USD, new BigDecimal("20"), NOW));
            assertThatThrownBy(() -> change.get(500, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            order.get(10, TimeUnit.SECONDS);
            change.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }
        assertThat(item(settings.view(owner, NOW), "order_amount_usd").value()).isEqualTo("20");
    }


    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }


    private static FinancialLimitsView.Item item(FinancialLimitsView view, String name) {
        return view.limits().stream().filter(item -> item.name().equals(name)).findFirst().orElseThrow();
    }


    private CurrentUser user() {
        var saved = users.saveAndFlush(AppUser.of(UUID.randomUUID() + "@example.test", "상한 검사", 1L, UserRole.MEMBER));
        return new CurrentUser(saved.id(), saved.email(), saved.displayName(), 1L, UserRole.MEMBER);
    }
}
