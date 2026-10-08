package com.bifos.assistant.usage.infra;

import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.domain.ConversationDelivery;
import com.bifos.assistant.usage.domain.CostByAgent;
import com.bifos.assistant.usage.domain.CostByDay;
import com.bifos.assistant.usage.domain.CostByFingerprint;
import com.bifos.assistant.usage.domain.CostByModel;
import com.bifos.assistant.usage.domain.ExecutionAgentRef;
import com.bifos.assistant.usage.domain.ExecutionSessionRef;
import com.bifos.assistant.usage.domain.MonthlyCost;
import com.bifos.assistant.usage.domain.MonthlyCostDetail;
import com.bifos.assistant.usage.domain.type.ExecutionStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AgentExecutionRepository extends JpaRepository<AgentExecution, Long> {

    /** 알 수 없는 자식 수 대신 사건 관측이 빠진 실행을 센다. 과거 UNKNOWN 과 실행 중인 줄은 뺀다. */
    @Query("""
            select count(e) from AgentExecution e
            where e.userId = :userId and e.startedAt >= :from and e.startedAt < :to
              and e.status <> com.bifos.assistant.usage.domain.type.ExecutionStatus.RUNNING
              and e.eventObservation = 'INCOMPLETE'
            """)
    long countIncompleteEventObservations(
            @Param("userId") Long userId, @Param("from") Instant from, @Param("to") Instant to);

    /** 실행 번호들의 에이전트 번호만 읽는다. 기억 목록이 남긴 에이전트를 보일 때 쓴다. */
    @Query("""
            select new com.bifos.assistant.usage.domain.ExecutionAgentRef(e.id, e.agentId)
            from AgentExecution e where e.id in :ids
            """)
    List<ExecutionAgentRef> findAgentRefs(@Param("ids") Collection<Long> ids);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from AgentExecution e where e.id = :id")
    Optional<AgentExecution> lockById(@Param("id") Long id);

    @Query("""
            select e from AgentExecution e where e.finishedAt >= :since
            and e.reasoningEffort is null and e.reasoningEffortSource = 'UNKNOWN'
            and e.reasoningDefaultsCheckedAt is null
            order by e.id desc
            """)
    List<AgentExecution> findUnknownReasoningDefaults(@Param("since") Instant since, Pageable pageable);

    List<AgentExecution> findByUserIdOrderByIdDesc(Long userId, Pageable pageable);

    /**
     * 루트 실행만 낸다.
     *
     * <p>흐름 하나가 실행 넷을 남기므로, 전부 내면 목록이 중간 산출물로 찬다. 자식은 실행 트리 화면에서
     * 본다. 월 비용 합계는 자식을 포함하고, 그 이유는 {@link #sumCostBetween} 이 적는다.
     */
    List<AgentExecution> findByUserIdAndRootExecutionIdIsNullOrderByIdDesc(Long userId, Pageable pageable);

    /** 시작 시각과 번호를 함께 써서 루트 실행을 안정적으로 다음 쪽부터 읽는다. */
    @Query("""
            select e from AgentExecution e
            where e.userId = :userId and e.rootExecutionId is null
              and (:startedAt is null
                or e.startedAt < :startedAt
                or (e.startedAt = :startedAt and e.id < :id))
            order by e.startedAt desc, e.id desc
            """)
    List<AgentExecution> findRootExecutionsBefore(
            @Param("userId") Long userId,
            @Param("startedAt") Instant startedAt,
            @Param("id") long id,
            Pageable pageable);

    List<AgentExecution> findByStatus(ExecutionStatus status);

    /** 사용자 에이전트와 대화에 묶이지 않은 시스템 실행이다. 기동 때 원격 종료를 확인한다. */
    List<AgentExecution> findByAgentIdIsNullAndConversationIdIsNullAndStatus(ExecutionStatus status);

    /**
     * 그 Hermes session 을 가진 그 profile 의 실행을 상태로 골라 둘까지만 읽는다.
     *
     * <p>MCP 호출의 부모 실행을 찾을 때 쓴다(ADR-032). 사용자로 먼저 거르지 않는다. 부모는 정확히 하나여야
     * 하므로 둘이 보이면 더 읽지 않아도 실패가 정해진다. {@code (hermes_session_id, status)} 색인을 탄다.
     */
    List<AgentExecution> findTop2ByHermesSessionIdAndStatusAndProfileName(
            String hermesSessionId, ExecutionStatus status, String profileName);

    /** 그 Hermes session 으로 그 상태인 실행이 profile 과 무관하게 하나라도 있는지. 거절 이유를 로그에서 나누려고 쓴다. */
    boolean existsByHermesSessionIdAndStatus(String hermesSessionId, ExecutionStatus status);

    /**
     * 그 profile 의 실행 줄이 그 Hermes session 을 상태와 무관하게 쓰는지.
     *
     * <p>하위 에이전트 session 을 등록할 때 최상위 session 을 거르려고 쓴다(ADR-037). 최상위 session 에 등록이
     * 생기면 뒤 turn 의 호출이 앞 turn 에 묶인다.
     */
    boolean existsByProfileNameAndHermesSessionId(String profileName, String hermesSessionId);

    /** 그 {@code delegation_key} 로 만든 위임 실행이다. 같은 도구 호출이 다시 왔는지 볼 때 쓴다(ADR-032). */
    Optional<AgentExecution> findByDelegationKey(String delegationKey);

    /**
     * 한 루트 아래에서 그 상태인 위임 실행의 수다. 위임 동시 한도를 셀 때 쓴다.
     *
     * <p>{@code delegation_key} 가 없는 자식(흐름의 하위 실행, Memory 제안)은 세지 않는다.
     */
    long countByRootExecutionIdAndStatusAndDelegationKeyIsNotNull(Long rootExecutionId, ExecutionStatus status);

    /** 한 루트 아래의 위임 실행 수다. 상태와 무관하게 센다. 먼저 살펴보기 한 번이 맡긴 수를 적을 때 쓴다(ADR-080). */
    long countByRootExecutionIdAndDelegationKeyIsNotNull(Long rootExecutionId);

    /** 그 사용자의 그 상태인 실행 줄 수다. 대화 turn 의 루트 줄은 turn 자리로 세므로 뺀다(ADR-069). */
    @Query("""
            select count(e) from AgentExecution e
            where e.userId = :userId
              and e.status = :status
              and (e.parentExecutionId is not null or e.conversationId is null)
            """)
    long countRunningOutsideTurns(@Param("userId") Long userId, @Param("status") ExecutionStatus status);

    /**
     * 이 실행의 결과를 부모에게 전했다고 적는다. 이미 적혀 있으면 바꾸지 않고 0 을 돌려준다.
     *
     * <p>한 실행의 결과는 한 번만 전한다. 먼저 적은 쪽의 시각이 남는다.
     *
     * <p>트랜잭션은 {@code ExecutionDeliveryWriter} 가 연다.
     */
    @Modifying
    @Query("update AgentExecution e set e.resultDeliveredAt = :at where e.id = :id and e.resultDeliveredAt is null")
    int markResultDelivered(@Param("id") Long id, @Param("at") Instant at);

    /**
     * 한 루트 아래의 위임 실행 가운데 결과를 전하지 않은 줄을 모두 전했다고 적고 적은 줄 수를 돌려준다.
     *
     * <p>상태는 보지 않는다. 아직 도는 줄도 적어, 나중에 끝나도 부모 대화를 깨우지 않는다. 먼저 살펴보기가 끝날 때 쓴다(ADR-080).
     * 이미 적힌 줄은 먼저 적은 시각을 그대로 둔다.
     *
     * <p>트랜잭션은 {@code ExecutionDeliveryWriter} 가 연다.
     */
    @Modifying
    @Query("""
            update AgentExecution e set e.resultDeliveredAt = :at
            where e.rootExecutionId = :rootExecutionId
                and e.delegationKey is not null
                and e.resultDeliveredAt is null
            """)
    int markTreeDelivered(@Param("rootExecutionId") Long rootExecutionId, @Param("at") Instant at);

    /**
     * 그 대화에 아직 전하지 않은 끝난 위임 결과를 오래된 순으로 읽는다.
     *
     * <p>대화 turn 이 직접 맡긴 실행만 낸다. 부모 실행이 다시 자식이면 손자 실행이라 빼고, 그 결과는 자식이
     * {@code agent_status} 로 읽는다. {@code CANCELLED} 는 사용자나 부모가 멈춘 것이라 전하지 않는다.
     */
    @Query("""
            select e from AgentExecution e
            where e.conversationId = :conversationId
                and e.delegationKey is not null
                and e.status in (com.bifos.assistant.usage.domain.type.ExecutionStatus.SUCCEEDED,
                    com.bifos.assistant.usage.domain.type.ExecutionStatus.FAILED)
                and e.resultDeliveredAt is null
                and exists (select p.id from AgentExecution p
                    where p.id = e.parentExecutionId and p.parentExecutionId is null)
            order by e.id asc
            """)
    List<AgentExecution> findUndeliveredResults(@Param("conversationId") Long conversationId);

    /** 아직 전하지 않은 끝난 위임 결과가 있는 대화의 번호다. 조건은 {@link #findUndeliveredResults} 와 같다. */
    @Query("""
            select distinct e.conversationId from AgentExecution e
            where e.conversationId is not null
                and e.delegationKey is not null
                and e.status in (com.bifos.assistant.usage.domain.type.ExecutionStatus.SUCCEEDED,
                    com.bifos.assistant.usage.domain.type.ExecutionStatus.FAILED)
                and e.resultDeliveredAt is null
                and exists (select p.id from AgentExecution p
                    where p.id = e.parentExecutionId and p.parentExecutionId is null)
            """)
    List<Long> findConversationsWithUndeliveredResults();

    /**
     * 그 사용자의 대화 turn 루트 실행 가운데 {@code since} 뒤에 실패했고 아직 다시 돌려 성공하지 않은 것을 최근 순으로 읽는다.
     *
     * <p>같은 대화에 그 뒤 성공한 루트 실행이 있으면 해결된 실패라 뺀다. 먼저 알리기의 실패 카드가 읽는다.
     */
    @Query("""
            select e from AgentExecution e
            where e.userId = :userId
                and e.conversationId is not null
                and e.parentExecutionId is null
                and e.status = com.bifos.assistant.usage.domain.type.ExecutionStatus.FAILED
                and e.finishedAt >= :since
                and not exists (select s.id from AgentExecution s
                    where s.conversationId = e.conversationId
                        and s.id > e.id
                        and s.parentExecutionId is null
                        and s.status = com.bifos.assistant.usage.domain.type.ExecutionStatus.SUCCEEDED)
            order by e.id desc
            """)
    List<AgentExecution> findUnresolvedFailedTurns(@Param("userId") Long userId, @Param("since") Instant since);

    /**
     * 그 사용자의 위임 실행 가운데 도는 중이거나 {@code finishedSince} 뒤에 끝난 것을 최근 순으로 읽는다.
     *
     * <p>먼저 알리기의 맡긴 일 카드가 읽는다.
     */
    @Query("""
            select e from AgentExecution e
            where e.userId = :userId
                and e.delegationKey is not null
                and (e.status = com.bifos.assistant.usage.domain.type.ExecutionStatus.RUNNING
                    or e.finishedAt >= :finishedSince)
            order by e.id desc
            """)
    List<AgentExecution> findDelegationsForAttention(
            @Param("userId") Long userId, @Param("finishedSince") Instant finishedSince);

    /** 루트와 그 자손을 한 번에 읽는다. 루트 자신은 rootExecutionId 가 null 이라 따로 읽는다. */
    List<AgentExecution> findByRootExecutionId(Long rootExecutionId);

    /** 여러 답의 자손 실행을 한 번에 읽는다. */
    List<AgentExecution> findByRootExecutionIdIn(Collection<Long> rootExecutionIds);

    /** 이 번호들 중 자식을 가진 것만 낸다. 목록이 실행마다 세지 않게 한 번에 읽는다. */
    @Query("""
            select distinct e.parentExecutionId from AgentExecution e
            where e.parentExecutionId in :parentIds
            """)
    List<Long> findParentIdsHavingChildren(@Param("parentIds") Collection<Long> parentIds);

    /**
     * 한 구간의 환산 금액을 데이터베이스에서 합친다.
     *
     * <p>줄을 다 읽어 와서 더하지 않는 이유는, 한 달치 실행 수가 화면이 보여 주는 50줄보다 훨씬 많아질 수
     * 있기 때문이다. 금액이 비어 있는 줄은 합계에 더해지지 않고 따로 세어진다.
     *
     * <p>실행 줄만 더한다. 실행 줄이 없는 native 자식은 {@code UsageSummaryService} 가 원장 줄로
     * 더한다(ADR-062).
     */
    @Query("""
            select new com.bifos.assistant.usage.domain.MonthlyCost(
                sum(e.estimatedCostMicros),
                sum(case when e.estimatedCostMicros is null then 0L else 1L end),
                sum(case when e.estimatedCostMicros is null then 1L else 0L end))
            from AgentExecution e
            where e.userId = :userId and e.startedAt >= :from and e.startedAt < :to
                and e.status <> com.bifos.assistant.usage.domain.type.ExecutionStatus.RUNNING
            """)
    MonthlyCost sumCostBetween(@Param("userId") Long userId, @Param("from") Instant from, @Param("to") Instant to);

    /**
     * 환산액과 실제 청구액을 함께 합친다. RUNNING 은 빠진다.
     *
     * <p>구독 경로 실행은 실제 청구액이 비어 있는지가 아니라 {@code costMode} 로 센다. 가격표에 없는
     * 모델로 돈 API 경로 실행은 환산액과 실제 청구액이 모두 비어 있어, 금액으로 세면 구독 경로로
     * 잘못 세어진다. 그런 실행은 {@code unpricedExecutions} 로만 세어진다.
     */
    @Query("""
            select new com.bifos.assistant.usage.domain.MonthlyCostDetail(
                sum(e.estimatedCostMicros),
                sum(e.actualCostMicros),
                sum(case when e.estimatedCostMicros is null then 0L else 1L end),
                sum(case when e.estimatedCostMicros is null then 1L else 0L end),
                sum(case when e.costMode = com.bifos.assistant.agent.domain.type.CostMode.SUBSCRIPTION then 1L else 0L end))
            from AgentExecution e
            where e.userId = :userId and e.startedAt >= :from and e.startedAt < :to
                and e.status <> com.bifos.assistant.usage.domain.type.ExecutionStatus.RUNNING
            """)
    MonthlyCostDetail sumCostDetailBetween(
            @Param("userId") Long userId, @Param("from") Instant from, @Param("to") Instant to);

    /**
     * 에이전트별 합계. RUNNING 은 빠진다.
     *
     * <p>에이전트 이름을 같은 질의에서 함께 읽는다. 줄마다 에이전트를 다시 찾으면 축 하나에 질의가
     * 실행 수만큼 늘어난다.
     *
     * <p>실행 줄을 전부 센다. 자식 토큰이 부모의 usage 에 포함되지 않는 것을 ADR-016 이 실측으로
     * 확정했으므로, 전부 세는 것이 실제 사용량이고 두 번 세어지지 않는다.
     * 실행 줄이 없는 native 자식은 {@code UsageSummaryService} 가 원장 줄로 더한다(ADR-062).
     */
    @Query("""
            select new com.bifos.assistant.usage.domain.CostByAgent(
                e.agentId,
                a.code,
                a.name,
                count(e),
                sum(e.estimatedCostMicros),
                sum(e.actualCostMicros),
                sum(e.inputTokens),
                sum(e.outputTokens),
                avg(e.contextChars),
                min(e.startedAt),
                max(e.startedAt))
            from AgentExecution e
                left join Agent a on a.id = e.agentId
            where e.userId = :userId and e.startedAt >= :from and e.startedAt < :to
                and e.status <> com.bifos.assistant.usage.domain.type.ExecutionStatus.RUNNING
            group by e.agentId, a.code, a.name
            order by sum(coalesce(e.estimatedCostMicros, 0L)) desc, e.agentId asc
            """)
    List<CostByAgent> sumByAgentBetween(
            @Param("userId") Long userId, @Param("from") Instant from, @Param("to") Instant to);

    /** 모델과 provider 별 합계. RUNNING 은 빠진다. */
    @Query("""
            select new com.bifos.assistant.usage.domain.CostByModel(
                e.provider,
                e.model,
                count(e),
                sum(e.estimatedCostMicros),
                sum(e.actualCostMicros),
                sum(e.inputTokens),
                sum(e.outputTokens),
                avg(e.contextChars),
                min(e.startedAt),
                max(e.startedAt))
            from AgentExecution e
            where e.userId = :userId and e.startedAt >= :from and e.startedAt < :to
                and e.status <> com.bifos.assistant.usage.domain.type.ExecutionStatus.RUNNING
            group by e.provider, e.model
            order by sum(coalesce(e.estimatedCostMicros, 0L)) desc, e.model asc
            """)
    List<CostByModel> sumByModelBetween(
            @Param("userId") Long userId, @Param("from") Instant from, @Param("to") Instant to);

    /**
     * 날짜별 합계. 하루 단위로 묶는다. RUNNING 은 빠진다.
     *
     * <p>저장된 값은 시간대가 없는 순간이고 화면이 보이려는 날짜는 가족이 사는 곳의 달력이라, 날짜를
     * 뽑기 전에 9시간을 더한다. {@code Asia/Seoul} 은 일광 절약 시간이 없어 표준시와의 차이가 늘 9시간
     * 이다. 그 값을 파라미터로 넘기면 H2 가 {@code group by} 의 식을 {@code select} 의 식과 같은
     * 것으로 보지 않아 질의가 거절된다.
     *
     * <p>같은 시간대를 {@code UsageController.HOUSEHOLD_ZONE} 도 갖는다. 그쪽은 달의 경계를 끊는 데
     * 쓰고 여기는 날짜를 뽑는 데 쓴다. 가족이 사는 곳이 바뀌면 두 자리를 함께 고친다.
     */
    @Query("""
            select new com.bifos.assistant.usage.domain.CostByDay(
                year(e.startedAt + 9 hour),
                month(e.startedAt + 9 hour),
                day(e.startedAt + 9 hour),
                count(e),
                sum(e.estimatedCostMicros),
                sum(e.actualCostMicros),
                sum(e.inputTokens),
                sum(e.outputTokens),
                avg(e.contextChars),
                min(e.startedAt),
                max(e.startedAt))
            from AgentExecution e
            where e.userId = :userId and e.startedAt >= :from and e.startedAt < :to
                and e.status <> com.bifos.assistant.usage.domain.type.ExecutionStatus.RUNNING
            group by
                year(e.startedAt + 9 hour),
                month(e.startedAt + 9 hour),
                day(e.startedAt + 9 hour)
            order by
                year(e.startedAt + 9 hour) asc,
                month(e.startedAt + 9 hour) asc,
                day(e.startedAt + 9 hour) asc
            """)
    List<CostByDay> sumByDayBetween(@Param("userId") Long userId, @Param("from") Instant from, @Param("to") Instant to);

    /**
     * 설정 지문별 합계. 무엇이 달라져서 비용이 움직였는지 본다. RUNNING 은 빠진다.
     *
     * <p>지문이 비어 있는 실행은 한 묶음으로 모으지 않고 통째로 뺀다. 지문을 모르는 것끼리 묶어도
     * 견줄 것이 없기 때문이다.
     */
    @Query("""
            select new com.bifos.assistant.usage.domain.CostByFingerprint(
                e.runtimeFingerprint,
                count(e),
                sum(e.estimatedCostMicros),
                sum(e.actualCostMicros),
                sum(e.inputTokens),
                sum(e.outputTokens),
                avg(e.contextChars),
                min(e.startedAt),
                max(e.startedAt))
            from AgentExecution e
            where e.userId = :userId and e.startedAt >= :from and e.startedAt < :to
                and e.status <> com.bifos.assistant.usage.domain.type.ExecutionStatus.RUNNING
                and e.runtimeFingerprint is not null
            group by e.runtimeFingerprint
            order by max(e.startedAt) desc, e.runtimeFingerprint asc
            """)
    List<CostByFingerprint> sumByFingerprintBetween(
            @Param("userId") Long userId, @Param("from") Instant from, @Param("to") Instant to);

    /**
     * 대화마다 위임 실행의 결과를 전한 가장 늦은 시각이다. 전한 실행이 없는 대화는 나오지 않는다.
     *
     * <p>{@code delegation_key} 가 없는 실행(대화 turn 의 루트, 흐름의 하위 실행)은 세지 않는다. 대화를 이어 가며 생기는
     * 실행이 결과 도착으로 보이면 안 된다. 먼저 알리기가 할 일에 연결한 대화의 결과 도착을 볼 때 쓴다.
     */
    @Query("""
            select new com.bifos.assistant.usage.domain.ConversationDelivery(
                e.conversationId, max(e.resultDeliveredAt))
            from AgentExecution e
            where e.conversationId in :conversationIds
                and e.delegationKey is not null and e.resultDeliveredAt is not null
            group by e.conversationId
            """)
    List<ConversationDelivery> findLastDeliveredByConversation(
            @Param("conversationIds") Collection<Long> conversationIds);

    /** 그 대화에 그 상태의 실행이 있는가. 지운 대화의 정리가 도는 실행을 기다릴 때 쓴다. */
    boolean existsByConversationIdAndStatus(Long conversationId, ExecutionStatus status);

    /** 그 대화의 실행이 보낸 Hermes session 을 겹치지 않게 읽는다. 지운 대화의 session 을 Hermes 에서 지울 때 쓴다. */
    @Query("""
            select distinct new com.bifos.assistant.usage.domain.ExecutionSessionRef(e.agentId, e.profileName, e.hermesSessionId)
              from AgentExecution e
             where e.conversationId = :conversationId and e.hermesSessionId is not null
            """)
    List<ExecutionSessionRef> findSessionRefs(@Param("conversationId") Long conversationId);

    /** 그 대화 실행의 답 본문을 비운다. 토큰과 금액은 남긴다. 트랜잭션은 {@code ConversationExecutionPurge} 가 연다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update AgentExecution e set e.outputText = null
             where e.conversationId = :conversationId and e.outputText is not null
            """)
    int clearOutputsOf(@Param("conversationId") Long conversationId);
}
