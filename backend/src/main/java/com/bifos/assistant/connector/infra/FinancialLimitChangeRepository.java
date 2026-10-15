package com.bifos.assistant.connector.infra;

import com.bifos.assistant.connector.domain.FinancialLimitChange;
import org.springframework.data.jpa.repository.JpaRepository;

/** 승인 줄 번호가 키인 상한 변경 요청이다. */
public interface FinancialLimitChangeRepository extends JpaRepository<FinancialLimitChange, Long> {
}
