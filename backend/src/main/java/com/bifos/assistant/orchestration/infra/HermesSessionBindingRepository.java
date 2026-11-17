package com.bifos.assistant.orchestration.infra;

import com.bifos.assistant.orchestration.domain.HermesSessionBinding;
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
}
