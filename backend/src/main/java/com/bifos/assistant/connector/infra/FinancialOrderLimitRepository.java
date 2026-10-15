package com.bifos.assistant.connector.infra;

import com.bifos.assistant.connector.domain.FinancialOrderLimit;
import org.springframework.data.jpa.repository.JpaRepository;

/** 사용자마다 한 줄인 주문 상한이다. 쓰는 쪽이 먼저 사용자 행을 잠근다. */
public interface FinancialOrderLimitRepository extends JpaRepository<FinancialOrderLimit, Long> {
}
