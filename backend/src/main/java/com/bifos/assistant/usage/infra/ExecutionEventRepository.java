package com.bifos.assistant.usage.infra;

import com.bifos.assistant.usage.domain.ExecutionEvent;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExecutionEventRepository extends JpaRepository<ExecutionEvent, Long> {

    /** 실행 하나의 사건을 일어난 순서대로 읽는다. `uk_execution_event_seq` 를 그대로 쓴다. */
    List<ExecutionEvent> findByExecutionIdOrderBySequenceAsc(Long executionId);
}
