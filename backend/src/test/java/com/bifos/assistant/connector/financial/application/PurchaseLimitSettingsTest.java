package com.bifos.assistant.connector.financial.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.connector.financial.domain.type.PurchaseLimitKind;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import com.bifos.assistant.user.domain.AppUser;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 화면이 구매 상한을 읽고 바꾸는 길이다(spec REQ9). 기본은 0 이고, 운영 최대치 없이 사용자 값이 그대로 적용된다. */
@BackendIntegrationTest
class PurchaseLimitSettingsTest {
    private static final Instant NOW = Instant.parse("2026-11-13T01:00:00Z");
    @Autowired
    PurchaseLimitSettings settings;
    @Autowired
    AppUserRepository users;
    CurrentUser owner;


    @BeforeEach
    void setUp() {
        var saved = users.saveAndFlush(AppUser.of(UUID.randomUUID() + "@example.test", "구매 상한 검사", 1L, UserRole.MEMBER));
        owner = new CurrentUser(saved.id(), saved.email(), saved.displayName(), 1L, UserRole.MEMBER);
    }


    @Test
    @DisplayName("새 사용자의 두 상한은 0 이고 오늘 남은 한도도 0 이다")
    void defaultsAreClosed() {
        var view = settings.view(owner);
        assertThat(view.orderKrw()).isZero();
        assertThat(view.dailyKrw()).isZero();
        assertThat(view.remainingTodayKrw()).isZero();
    }


    @Test
    @DisplayName("올리기와 낮추기가 바로 반영되고 한 종류를 바꿔도 다른 종류를 지우지 않는다")
    void raisesAndLowersEachKind() {
        settings.set(owner, PurchaseLimitKind.ORDER, 50_000L, NOW);
        var daily = settings.set(owner, PurchaseLimitKind.DAILY, 120_000L, NOW);
        assertThat(daily.orderKrw()).isEqualTo(50_000L);
        assertThat(daily.dailyKrw()).isEqualTo(120_000L);
        assertThat(daily.remainingTodayKrw()).isEqualTo(120_000L);
        var lowered = settings.set(owner, PurchaseLimitKind.ORDER, 0L, NOW);
        assertThat(lowered.orderKrw()).isZero();
        assertThat(settings.view(owner).dailyKrw()).isEqualTo(120_000L);
    }


    @Test
    @DisplayName("0 보다 작거나 1조 이상인 값은 저장하지 않고 VALIDATION_FAILED 다")
    void rejectsOutOfRange() {
        settings.set(owner, PurchaseLimitKind.DAILY, 10_000L, NOW);
        assertThatThrownBy(() -> settings.set(owner, PurchaseLimitKind.DAILY, -1L, NOW))
            .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        assertThatThrownBy(() -> settings.set(owner, PurchaseLimitKind.DAILY, 1_000_000_000_000L, NOW))
            .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        assertThat(settings.view(owner).dailyKrw()).isEqualTo(10_000L);
    }


    @Test
    @DisplayName("상한을 바꾸기 전에 읽어 둔 사용자를 저장해도 상한은 옛 값으로 돌아가지 않는다")
    void staleUserSaveKeepsLimits() {
        AppUser stale = users.findById(owner.id()).orElseThrow();
        settings.set(owner, PurchaseLimitKind.ORDER, 30_000L, NOW);
        stale.rename("이름 바꿈");
        users.saveAndFlush(stale);
        assertThat(settings.view(owner).orderKrw()).isEqualTo(30_000L);
    }
}
