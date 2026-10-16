package com.bifos.assistant.connector.infra;

import com.bifos.assistant.connector.domain.FinancialOrderLimit;
import com.bifos.assistant.user.domain.AppUser;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * 사용자의 {@code app_user} 컬럼에 둔 주문 상한을 읽고 쓰는 전용 쿼리다. 쓰는 쪽이 먼저 사용자 행을 잠근다.
 *
 * <p>{@link AppUser} 는 이 컬럼을 넣기와 고치기에서 뺀다. 일반 사용자 저장이 먼저 읽어 둔 옛 값으로 상한을 덮지 않게 하려는 것이다.
 */
public interface FinancialOrderLimitRepository extends Repository<AppUser, Long> {

    /** DB 의 지금 값을 읽는다. 같은 트랜잭션에서 먼저 읽어 둔 {@link AppUser} 엔티티를 거치지 않는다. 사용자가 없으면 빈 값이다. */
    @Query("select new com.bifos.assistant.connector.domain.FinancialOrderLimit(u.limitOrderAmountKrw, u.limitOrderAmountUsd, "
        + "u.limitOrderQuantity, u.limitDailyAmountKrw, u.limitDailyAmountUsd) from AppUser u where u.id = :userId")
    Optional<FinancialOrderLimit> findByUserId(@Param("userId") Long userId);


    /** 다섯 값과 갱신 시각을 함께 쓴다. 고친 줄 수를 돌려준다. */
    @Modifying(flushAutomatically = true)
    @Query(nativeQuery = true, value = "UPDATE app_user SET limit_order_amount_krw = :orderAmountKrw, "
        + "limit_order_amount_usd = :orderAmountUsd, limit_order_quantity = :orderQuantity, "
        + "limit_daily_amount_krw = :dailyAmountKrw, limit_daily_amount_usd = :dailyAmountUsd, limit_updated_at = :now "
        + "WHERE id = :userId")
    int store(
        @Param("userId") Long userId,
        @Param("orderAmountKrw") BigDecimal orderAmountKrw,
        @Param("orderAmountUsd") BigDecimal orderAmountUsd,
        @Param("orderQuantity") BigDecimal orderQuantity,
        @Param("dailyAmountKrw") BigDecimal dailyAmountKrw,
        @Param("dailyAmountUsd") BigDecimal dailyAmountUsd,
        @Param("now") Instant now);
}
