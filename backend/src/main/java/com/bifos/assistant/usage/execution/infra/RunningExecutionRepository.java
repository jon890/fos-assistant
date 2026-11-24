package com.bifos.assistant.usage.execution.infra;

import com.bifos.assistant.usage.execution.domain.AgentExecution;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * 어느 시각에 돌던 실행을 찾는다. plugin 의 실행 기록을 실행에 붙일 때 쓴다(docs/features/run-trace/design.md 「session 을 실행으로
 * 푼다」).
 *
 * <p>{@link AgentExecutionRepository} 가 파일 길이 상한에 닿아 따로 둔다. 같은 엔티티를 읽기만 한다.
 */
public interface RunningExecutionRepository extends Repository<AgentExecution, Long> {

    /**
     * 그 시각에 그 Hermes session 으로 돌던 그 profile 의 실행이다. 늦게 시작한 것부터 페이지 크기만큼 읽는다.
     *
     * <p>대화의 turn 은 같은 루트 session 을 쓰므로 도는 실행만 찾으면 끝난 직후 도착한 기록이 다음 turn 에 붙는다. 그래서 도구가 시작한
     * 시각으로 고른다. {@code (hermes_session_id, status)} 색인의 앞 칸을 탄다.
     */
    @Query("""
            select e from AgentExecution e
             where e.profileName = :profileName and e.hermesSessionId = :sessionId
               and e.startedAt <= :startedBefore
               and (e.finishedAt is null or e.finishedAt >= :finishedAfter)
             order by e.startedAt desc
            """)
    List<AgentExecution> findRunningAt(
        @Param("profileName") String profileName,
        @Param("sessionId") String sessionId,
        @Param("startedBefore") Instant startedBefore,
        @Param("finishedAfter") Instant finishedAfter,
        Pageable page);
}
