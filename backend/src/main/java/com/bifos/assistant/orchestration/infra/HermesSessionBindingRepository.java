package com.bifos.assistant.orchestration.infra;

import com.bifos.assistant.orchestration.domain.HermesSessionBinding;
import java.time.Instant;
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
}
