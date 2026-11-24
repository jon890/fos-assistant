package com.bifos.assistant.usage.execution.infra;

import com.bifos.assistant.usage.execution.domain.ExecutionEvent;
import com.bifos.assistant.usage.execution.domain.type.ExecutionEventType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExecutionEventRepository extends JpaRepository<ExecutionEvent, Long> {

    @Query("""
            select event from ExecutionEvent event, AgentExecution parent
            where event.executionId = parent.id and parent.finishedAt is not null
            and parent.finishedAt >= :since and event.eventType = 'SUBAGENT_STARTED'
            and event.hermesSessionId is not null
            and not exists (select job.id from SubagentUsageJob job
                where job.profileName = parent.profileName and job.childSessionId = event.hermesSessionId)
            order by event.id
            """)
    List<ExecutionEvent> findUnscheduledChildren(@Param("since") Instant since, Pageable pageable);


    /**
     * 한 구간에 시작한 실행에서 session 없이 온 하위 에이전트 시작 사건의 수다.
     *
     * <p>session 이 없으면 사용량을 조회할 길이 없어 그 자식의 금액은 끝내 확인하지 못한다. 부모가 RUNNING
     * 이면 세지 않는다.
     */
    @Query("""
            select count(event) from ExecutionEvent event, AgentExecution e
            where event.executionId = e.id
                and e.userId = :userId and e.startedAt >= :from and e.startedAt < :to
                and e.status <> 'RUNNING'
                and event.eventType = 'SUBAGENT_STARTED' and event.hermesSessionId is null
            """)
    long countSessionlessChildren(@Param("userId") Long userId, @Param("from") Instant from, @Param("to") Instant to);


    /**
     * 한 구간에 시작한 실행에서 session 은 있는데 원장 줄이 아직 없는 자식의 수다.
     *
     * <p>부모가 끝난 시각으로 한 번 더 거른다. {@link #findUnscheduledChildren} 이 찾는 구간 안이면 곧 줄이
     * 생길 자식이고, 그 구간이 지났으면 더는 찾지 않는 자식이다. 같은 자식의 시작 사건이 겹쳐 와도 한 번만
     * 센다.
     */
    @Query("""
            select count(distinct event.hermesSessionId) from ExecutionEvent event, AgentExecution e
            where event.executionId = e.id
                and e.userId = :userId and e.startedAt >= :from and e.startedAt < :to
                and e.status <> 'RUNNING'
                and e.finishedAt >= :finishedFrom and e.finishedAt < :finishedBefore
                and event.eventType = 'SUBAGENT_STARTED' and event.hermesSessionId is not null
                and not exists (select job.id from SubagentUsageJob job
                    where job.profileName = e.profileName and job.childSessionId = event.hermesSessionId)
            """)
    long countUnscheduledChildren(
        @Param("userId") Long userId,
        @Param("from") Instant from,
        @Param("to") Instant to,
        @Param("finishedFrom") Instant finishedFrom,
        @Param("finishedBefore") Instant finishedBefore);


    boolean existsByExecutionIdAndHermesSessionIdAndEventType(
        Long executionId, String hermesSessionId, ExecutionEventType eventType);


    @Query("select coalesce(max(event.sequence), 0) from ExecutionEvent event where event.executionId = :id")
    int lastSequence(@Param("id") Long id);


    /**
     * 트리에 담긴 실행들의 사건을 일어난 순서대로 한 번에 읽는다. {@code uk_execution_event_seq} 를
     * 그대로 쓴다.
     *
     * <p>노드마다 따로 읽으면 질의가 노드 수만큼 늘어난다. 실행 번호가 비어 있으면 부르지 않는다. 빈
     * {@code in} 절은 데이터베이스마다 다르게 동작한다.
     */
    List<ExecutionEvent> findByExecutionIdInOrderByExecutionIdAscSequenceAsc(Collection<Long> executionIds);


    /**
     * 그 대화의 실행 가운데 바깥 글을 읽었을 수 있는 도구나 하위 에이전트를 시작한 것이 있는가(ADR-20261007 / memory-remember).
     *
     * <p>Hermes session 은 대화의 앞 turn 들을 이력으로 이어 가므로 지금 실행 하나가 아니라 대화 전체를 본다.
     * {@code internalTools} 에 든 도구의 시작 사건만 빼고 센다. 이름이 없는 도구 시작 사건도 바깥 도구로 본다.
     */
    @Query("""
            select case when count(event) > 0 then true else false end from ExecutionEvent event, AgentExecution e
            where event.executionId = e.id and e.conversationId = :conversationId
                and (event.eventType = 'SUBAGENT_STARTED'
                    or (event.eventType = 'TOOL_STARTED'
                        and (event.toolName is null or event.toolName not in :internalTools)))
            """)
    boolean existsOutsideToolStartInConversation(
        @Param("conversationId") Long conversationId, @Param("internalTools") Collection<String> internalTools);


    /**
     * 그 대화의 실행이 낳은 자식 가운데 아직 사용량 작업 줄이 없는 것이 있는가. {@link #findUnscheduledChildren} 과 같은 조건을 그
     * 대화로 한정한다. 작업 줄이 생기기 전에 Hermes session 을 지우면 자식 사용량을 읽지 못한다.
     */
    @Query("""
            select case when count(event) > 0 then true else false end
              from ExecutionEvent event, AgentExecution parent
             where event.executionId = parent.id and parent.conversationId = :conversationId
               and parent.finishedAt is not null and parent.finishedAt >= :since
               and event.eventType = 'SUBAGENT_STARTED' and event.hermesSessionId is not null
               and not exists (select job.id from SubagentUsageJob job
                   where job.profileName = parent.profileName and job.childSessionId = event.hermesSessionId)
            """)
    boolean existsUnscheduledChildInConversation(
        @Param("conversationId") Long conversationId, @Param("since") Instant since);


    /**
     * plugin 의 도구 기록을 이미 저장된 도구 완료 줄 하나에 옮긴다. 바꾸는 칸만 쓴다.
     *
     * <p>엔티티를 읽어 통째로 저장하면 그 사이 대화 삭제가 비운 {@code detail} 을 다시 쓴다. 걸린 시간은 비었을 때만 채우고, 오류 두 칸은
     * 실패 표시가 참인 줄에만 쓴다. 도구 이름이 다르면 바꾸지 않는다. Tool Search 의 {@code tool_call} 은 사건이 다리 이름을 보내므로
     * 이름을 보지 않는다(docs/features/run-trace/design.md 「짝짓기 장부」).
     *
     * <p>트랜잭션은 {@code ToolTraceRows} 가 연다.
     *
     * @return 바꾼 줄 수
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update ExecutionEvent ev
               set ev.durationMs = coalesce(ev.durationMs, :durationMs),
                   ev.errorType = case when ev.failed = true then coalesce(:errorType, ev.errorType) else ev.errorType end,
                   ev.errorSummary = case when ev.failed = true then coalesce(:errorSummary, ev.errorSummary)
                                     else ev.errorSummary end
             where ev.id = :id and ev.eventType = 'TOOL_COMPLETED'
               and (:toolName is null or ev.toolName is null or ev.toolName = :toolName or ev.toolName = 'tool_call')
            """)
    int recordToolTrace(
        @Param("id") Long id,
        @Param("toolName") String toolName,
        @Param("durationMs") Long durationMs,
        @Param("errorType") String errorType,
        @Param("errorSummary") String errorSummary);


    /**
     * 그 대화 실행의 사건에서 {@code detail} 과 오류 요약을 비운다. 사건 줄, 토큰, 오류 종류는 남긴다.
     *
     * <p>트랜잭션은 {@code ConversationExecutionPurge} 가 연다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update ExecutionEvent ev set ev.detail = null, ev.errorSummary = null
             where (ev.detail is not null or ev.errorSummary is not null)
               and ev.executionId in (select e.id from AgentExecution e where e.conversationId = :conversationId)
            """)
    int clearDetailsOf(@Param("conversationId") Long conversationId);
}
