package com.bifos.assistant.usage.infra;

import com.bifos.assistant.usage.domain.ExecutionEvent;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExecutionEventRepository extends JpaRepository<ExecutionEvent, Long> {

    /** 실행 하나의 사건을 일어난 순서대로 읽는다. `uk_execution_event_seq` 를 그대로 쓴다. */
    List<ExecutionEvent> findByExecutionIdOrderBySequenceAsc(Long executionId);

    /**
     * 나무에 담긴 실행들의 사건을 한 번에 읽는다.
     *
     * <p>노드마다 따로 읽으면 질의가 노드 수만큼 늘어난다. 실행 번호가 비어 있으면 부르지 않는다. 빈
     * {@code in} 절은 데이터베이스마다 다르게 동작한다.
     */
    List<ExecutionEvent> findByExecutionIdInOrderByExecutionIdAscSequenceAsc(
            Collection<Long> executionIds);
}
