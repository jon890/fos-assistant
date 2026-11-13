package com.bifos.assistant.connector.financial.application;

import com.bifos.assistant.connector.financial.application.dto.PurchaseLimitsView;
import com.bifos.assistant.connector.financial.domain.PurchaseLimit;
import com.bifos.assistant.connector.financial.domain.type.PurchaseLimitKind;
import com.bifos.assistant.connector.financial.infra.PurchaseLimitRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사용자가 자기 구매 상한을 읽고 바꾸는 곳이다(ADR-20261113 / purchase-checkout).
 *
 * <p>운영 최대치가 없어 사용자 값이 곧 적용 상한이다(spec DECISION31). 바꾸는 쪽은 주문서 준비와 승인처럼 먼저 사용자 행을 잠근다.
 * 그래서 주문서 판정과 상한 변경이 차례로 일어난다. 화면의 변경은 사용자의 직접 조작이라 승인 카드를 만들지 않는다.
 * 에이전트는 이 경로를 부를 수 없다(spec DECISION15).
 */
@Component
@RequiredArgsConstructor
public class PurchaseLimitSettings {
    /** 1조 원 미만이다. 화면의 입력 실수로 터무니없는 값이 들어가지 않게 한다. */
    static final long MAX_VALUE = 999_999_999_999L;
    private final PurchaseLimitRepository limits;
    private final AppUserRepository users;
    private final PurchaseLimits purchases;
    private final Clock clock;


    /**
     * 그 사용자의 상한과 오늘 합계다. 하루 합계는 {@code PURCHASE} 승인 줄에서 센다({@link PurchaseLimits#spentToday}).
     * 세는 규칙은 {@code backend/docs/data-schema.md} 의 「구매 상한 컬럼」 이 갖는다.
     */
    @Transactional(readOnly = true)
    public PurchaseLimitsView view(CurrentUser user) {
        PurchaseLimit stored = limits.findByUserId(user.id()).orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN, "no user"));
        long spent = purchases.spentToday(user.id(), null, clock.instant());
        return new PurchaseLimitsView(stored.orderKrw(), stored.dailyKrw(), spent, Math.max(0, stored.dailyKrw() - spent));
    }


    /**
     * 화면에서 상한 하나를 바꾼다. 올리기도 바로 반영한다.
     *
     * @throws ApiException 값이 0 보다 작거나 1조 이상이면 {@code VALIDATION_FAILED}
     */
    @Transactional
    public PurchaseLimitsView set(CurrentUser user, PurchaseLimitKind kind, long value, Instant now) {
        if (value < 0 || value > MAX_VALUE) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "purchase limit must be a non-negative won amount");
        }
        users.findByIdForUpdate(user.id()).orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN, "no user"));
        // 잠근 뒤 다시 읽으므로 다른 종류의 값을 옛 값으로 덮지 않는다.
        PurchaseLimit row = limits.findByUserId(user.id()).orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN, "no user"))
            .with(kind, value);
        limits.store(user.id(), row.orderKrw(), row.dailyKrw(), now.truncatedTo(ChronoUnit.MICROS));
        return view(user);
    }
}
