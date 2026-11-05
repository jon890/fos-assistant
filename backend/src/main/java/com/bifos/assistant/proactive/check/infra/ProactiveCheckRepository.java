package com.bifos.assistant.proactive.check.infra;

import com.bifos.assistant.proactive.check.domain.ProactiveCheck;
import com.bifos.assistant.proactive.check.domain.type.CheckStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProactiveCheckRepository extends JpaRepository<ProactiveCheck, Long> {

    /** 그 사용자의 그 에이전트의 마지막 살펴보기. 상태 조회와 일정 응답의 「마지막 살펴보기」 를 읽는다. */
    Optional<ProactiveCheck> findFirstByUserIdAndAgentIdOrderByIdDesc(Long userId, Long agentId);


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


    /** 그 점검 대화에서 그 상태로 끝난 마지막 살펴보기. 기억에서 꺼낸 최근 일의 시작 경계를 읽는다. */
    Optional<ProactiveCheck> findFirstByConversationIdAndStatusOrderByIdDesc(Long conversationId, CheckStatus status);


    /**
     * 그 점검 대화에서 그 시각 뒤에 시작해 보고를 남긴 살펴보기를 최근 것부터 읽는다. 되풀이 판정과 입력의 「최근 보고에서 알린 발견」 이 보고에
     * 담긴 판정 재료를 읽는다.
     */
    List<ProactiveCheck> findByConversationIdAndReportIsNotNullAndStartedAtAfterOrderByIdDesc(
        Long conversationId, Instant after);


    /**
     * 지운 에이전트를 정리할 때 그 에이전트의 살펴보기를 모두 지운다. 지운 행 수를 돌려준다.
     *
     * <p>외래 키로 이 살펴보기를 가리키는 표는 없다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from ProactiveCheck c where c.agentId = :agentId")
    int deleteAllOfAgent(@Param("agentId") Long agentId);


    /**
     * 사용자 정리 때 그 사용자의 먼저 살펴보기 점검을 모두 지운다(ADR-20261101 / user-purge). 지운 행 수를 돌려준다.
     *
     * <p>외래 키로 이 살펴보기를 가리키는 표는 없다. 반응 기록은 살펴보기 줄이 아니라 실행 번호로 잇는다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from ProactiveCheck c where c.userId = :userId")
    int deleteAllOfUser(@Param("userId") Long userId);
}
