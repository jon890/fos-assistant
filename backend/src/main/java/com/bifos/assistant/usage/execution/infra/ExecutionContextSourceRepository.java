package com.bifos.assistant.usage.execution.infra;

import com.bifos.assistant.usage.execution.domain.ExecutionContextSource;
import com.bifos.assistant.usage.execution.domain.ExecutionContextSourceId;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExecutionContextSourceRepository
    extends
        JpaRepository<ExecutionContextSource, ExecutionContextSourceId> {

    /** 실행 하나에 실은 항목을 실은 순서대로 읽는다. */
    List<ExecutionContextSource> findByIdExecutionIdOrderByIdPositionAsc(Long executionId);


    /**
     * 트리에 담긴 실행들의 항목을 한 번에 읽는다. 기본 키 순서 그대로다.
     *
     * <p>실행 번호가 비어 있으면 부르지 않는다. 빈 {@code in} 절은 데이터베이스마다 다르게 동작한다.
     */
    List<ExecutionContextSource> findByIdExecutionIdInOrderByIdExecutionIdAscIdPositionAsc(
        Collection<Long> executionIds);


    /**
     * 실행들의 항목 가운데 그 출처들이고 그 방식으로 실은 줄만 실행 번호, 실은 순서대로 읽는다. 걸러진 줄은 읽지 않는다.
     *
     * <p>실행 번호나 출처가 비어 있으면 부르지 않는다. 빈 {@code in} 절은 데이터베이스마다 다르게 동작한다.
     */
    List<ExecutionContextSource> findByIdExecutionIdInAndSourceInAndBodyModeOrderByIdExecutionIdAscIdPositionAsc(
        Collection<Long> executionIds, Collection<String> sources, String bodyMode);


    /**
     * 그때 뒤에 실린 줄의 원래 기록 참조를 중복 없이 낸다. 출처와 방식은 가리지 않는다. 정비 규칙이 기간 안에 한 번이라도 실린 기억을
     * 가려내려고 읽는다.
     */
    @Query("select distinct source.sourceRef from ExecutionContextSource source"
        + " where source.createdAt >= :since and source.sourceRef like 'memory:%'")
    List<String> findMemoryRefsLoadedSince(@Param("since") Instant since);


    /** 실행 하나의 마지막 순서다. 줄이 없으면 null 이다. */
    @Query("select max(source.id.position) from ExecutionContextSource source where source.id.executionId = :executionId")
    Integer lastPosition(@Param("executionId") Long executionId);
}
