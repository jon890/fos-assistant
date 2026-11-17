package com.bifos.assistant.orchestration.infra;

import com.bifos.assistant.orchestration.domain.HermesSessionBinding;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HermesSessionBindingRepository extends JpaRepository<HermesSessionBinding, Long> {

    /** {@code (profile_name, session_id)} 유일 제약을 탄다. */
    Optional<HermesSessionBinding> findByProfileNameAndSessionId(String profileName, String sessionId);


    /**
     * 깨울 자식의 맡긴 일 앞부분을 적는다. 아직 비어 있는 줄만 바꾼다. AAD 에 줄 번호가 들어가 저장한 뒤에 부른다.
     *
     * @param keyId 암호화한 데이터 key. 평문이면 null
     * @return 바꾼 줄 수
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "update hermes_session_binding set goal_preview = :goal, result_key_id = :keyId"
        + " where id = :id and wake_eligible = true and goal_preview is null", nativeQuery = true)
    int sealGoal(@Param("id") Long id, @Param("goal") String goal, @Param("keyId") Long keyId);


    /**
     * 깨울 자식의 끝 소식을 적는다. 아직 소식이 없는 줄만 바꾼다. 다시 보낸 소식과 동시 요청은 하나만 적힌다.
     *
     * @param deliveredAt 설정이 꺼져 전하지 않기로 했으면 받은 시각, 아니면 null
     * @return 바꾼 줄 수
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "update hermes_session_binding set stopped_at = :stoppedAt, result_status = :status,"
        + " result_text = :text, result_key_id = :keyId, goal_preview = :goal, result_delivered_at = :deliveredAt"
        + " where id = :id and wake_eligible = true and stopped_at is null", nativeQuery = true)
    int recordStop(
        @Param("id") Long id,
        @Param("stoppedAt") Instant stoppedAt,
        @Param("status") String status,
        @Param("text") String text,
        @Param("keyId") Long keyId,
        @Param("goal") String goal,
        @Param("deliveredAt") Instant deliveredAt);


    /**
     * 그 대화 실행을 origin 으로 둔 깨울 자식 가운데 끝 소식이 {@code settledBefore} 이전에 왔고 아직 전하지 않았으며 origin 이 중지되지
     * 않은 줄이다. 끝난 순서다. 이미 읽었는지는 부르는 쪽이 따로 본다.
     */
    @Query(value = "select b.* from hermes_session_binding b join agent_execution e on e.id = b.origin_execution_id"
        + " where e.conversation_id = :conversationId and b.wake_eligible = true and b.stopped_at is not null"
        + " and b.result_delivered_at is null and b.stopped_at <= :settledBefore and e.status <> 'CANCELLED'"
        + " order by b.stopped_at, b.id", nativeQuery = true)
    List<HermesSessionBinding> findSettledUndelivered(
        @Param("conversationId") Long conversationId, @Param("settledBefore") Instant settledBefore);


    /** {@link #findSettledUndelivered} 와 같은 조건의 줄이 있는 대화들이다. 기동할 때 훑는다. */
    @Query(value = "select distinct e.conversation_id from hermes_session_binding b"
        + " join agent_execution e on e.id = b.origin_execution_id"
        + " where e.conversation_id is not null and b.wake_eligible = true and b.stopped_at is not null"
        + " and b.result_delivered_at is null and b.stopped_at <= :settledBefore and e.status <> 'CANCELLED'",
        nativeQuery = true)
    List<Long> findConversationsWithSettledUndelivered(@Param("settledBefore") Instant settledBefore);


    /** 그 대화와 그 사용자의 끝 소식이 있는 깨울 자식 줄이다. 다시 전달할 때 읽는다. */
    @Query(value = "select b.* from hermes_session_binding b join agent_execution e on e.id = b.origin_execution_id"
        + " where b.id in (:ids) and b.user_id = :userId and e.conversation_id = :conversationId"
        + " and b.wake_eligible = true and b.stopped_at is not null", nativeQuery = true)
    List<HermesSessionBinding> findStoppedIn(
        @Param("conversationId") Long conversationId, @Param("userId") Long userId, @Param("ids") Collection<Long> ids);


    /** 아직 전하지 않은 깨울 자식 줄만 전했다고 적는다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "update hermes_session_binding set result_delivered_at = :now"
        + " where id in (:ids) and wake_eligible = true and result_delivered_at is null", nativeQuery = true)
    int markDelivered(@Param("ids") Collection<Long> ids, @Param("now") Instant now);


    /** 이 결과를 처음 전한 알림 줄을 적는다. 다시 전달은 바꾸지 않도록 비어 있을 때만 적는다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "update hermes_session_binding set notice_message_id = :messageId"
        + " where id = :id and notice_message_id is null", nativeQuery = true)
    int markNoticed(@Param("id") Long id, @Param("messageId") Long messageId);
}
