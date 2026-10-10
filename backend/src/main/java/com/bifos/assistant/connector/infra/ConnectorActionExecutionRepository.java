package com.bifos.assistant.connector.infra;

import com.bifos.assistant.connector.domain.ConnectorActionExecution;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** 승인 이력에 속한 실행 내용이다. 같은 의도 조회는 일반 인덱스를 사용한다. */
public interface ConnectorActionExecutionRepository extends JpaRepository<ConnectorActionExecution, Long> {
    List<ConnectorActionExecution> findByRequestKeyOrderByActionIdAsc(String requestKey);
}
