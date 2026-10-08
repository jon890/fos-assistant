package com.bifos.assistant.proactive.infra;

import com.bifos.assistant.proactive.domain.ProactiveCheck;
import com.bifos.assistant.proactive.domain.type.CheckStatus;
import com.bifos.assistant.proactive.domain.type.CheckTrigger;
import com.bifos.assistant.proactive.domain.type.ProblemStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface ProactiveCheckRepository extends JpaRepository<ProactiveCheck, Long> {

    Optional<ProactiveCheck> findByIdAndUserId(Long id, Long userId);

    /** 그 사용자가 그 에이전트로 연 마지막 살펴보기. */
    Optional<ProactiveCheck> findFirstByUserIdAndAgentIdOrderByIdDesc(Long userId, Long agentId);

    boolean existsByRootExecutionIdAndTrigger(Long rootExecutionId, CheckTrigger trigger);

    /** 마지막 살펴보기를 보일 때 자동 실행으로 시작한 줄은 뺀다. 그 결과는 사용자에게 바로 알리지 않는다. */
    Optional<ProactiveCheck> findFirstByUserIdAndAgentIdAndTriggerNotOrderByIdDesc(
            Long userId, Long agentId, CheckTrigger trigger);

    boolean existsByUserIdAndAgentIdAndReportIsNotNullAndReportOpenedAtIsNull(Long userId, Long agentId);

    boolean existsByConversationIdAndReportIsNotNullAndReportOpenedAtIsNull(Long conversationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ProactiveCheck> findByIdAndUserIdAndReportIsNotNull(Long id, Long userId);

    List<ProactiveCheck> findByUserIdAndReportIsNotNullAndReportOpenedAtIsNullOrderByStartedAtDesc(Long userId);

    /** 그 사용자가 그 점검 대화에서 아직 열지 않은 보고. 점검 대화를 읽으면 모두 연 것으로 적는다. */
    List<ProactiveCheck> findByUserIdAndConversationIdAndReportIsNotNullAndReportOpenedAtIsNull(
            Long userId, Long conversationId);

    /** 그 실행 줄이 살펴보기 turn 인지. 살펴보기 트리를 가리는 데 쓴다. */
    boolean existsByRootExecutionId(Long rootExecutionId);

    Optional<ProactiveCheck> findByRootExecutionId(Long rootExecutionId);

    /** 그 점검 대화의 그 루트 session 으로 보낸 살펴보기 수. session 을 바꿀지 셀 때 쓴다. */
    long countByConversationIdAndHermesRootSessionId(Long conversationId, String hermesRootSessionId);

    /** 그 상태의 살펴보기. 기동할 때 {@code RUNNING} 으로 남은 줄을 찾는다. */
    List<ProactiveCheck> findByStatus(CheckStatus status);

    /**
     * 그 점검 대화에서 그 상태가 아니고 모델을 부른 마지막 살펴보기. 변화 신호의 「지난 살펴보기」 를 읽는다. 모델 없이 건너뛴 줄은 지난
     * 결과도 루트 실행도 없으므로 뺀다.
     */
    Optional<ProactiveCheck> findFirstByConversationIdAndStatusNotAndSkippedReasonIsNullOrderByIdDesc(
            Long conversationId, CheckStatus status);

    /** 그 사용자가 그 시각 뒤에 연 살펴보기. 판단 피드백의 replay 읽기 모델이 상황으로 읽는다. */
    List<ProactiveCheck> findByUserIdAndStartedAtGreaterThanEqualOrderByIdAsc(Long userId, Instant since);

    List<ProactiveCheck> findByUserIdAndIdIn(Long userId, Collection<Long> ids);

    /** 그 사용자의 그 루트 실행들의 살펴보기. 제안을 낸 실행에서 살펴보기를 찾는다. */
    List<ProactiveCheck> findByUserIdAndRootExecutionIdIn(Long userId, Collection<Long> rootExecutionIds);

    /**
     * 그 사용자가 그 에이전트로 연, 평가할 수 있는 살펴보기를 최근 것부터 읽는다. 끝났고 자동 실행이 연 줄이 아니며 받아들인 문제 후보가 있고 점검
     * 대화를 지우지 않은 줄만 읽는다. 개수는 {@code page} 가 정한다.
     */
    default List<ProactiveCheck> findEvaluable(Long userId, Long agentId, Pageable page) {
        return findEvaluable(
                userId, agentId, CheckStatus.SUCCEEDED, CheckTrigger.AUTONOMY, ProblemStatus.ACCEPTED, page);
    }

    /**
     * {@link #findEvaluable(Long, Long, Pageable)} 의 조회다. 직접 부르지 않는다. 3인자 default 메서드가 상태와 계기 값을 정해 부른다. 값을
     * JPQL 에 전체 이름으로 쓰지 않고 넘기는 까닭은 코드 규칙이 전체 이름을 막기 때문이다.
     */
    @Query("""
            select c from ProactiveCheck c
             where c.userId = :userId and c.agentId = :agentId
               and c.status = :status and c.trigger <> :excludedTrigger
               and exists (select p.id from ProactiveCheckProblem p
                            where p.checkId = c.id and p.status = :problemStatus)
               and exists (select v.id from Conversation v
                            where v.id = c.conversationId and v.userId = :userId and v.deletedAt is null)
             order by c.id desc
            """)
    List<ProactiveCheck> findEvaluable(
            Long userId,
            Long agentId,
            CheckStatus status,
            CheckTrigger excludedTrigger,
            ProblemStatus problemStatus,
            Pageable page);
}
