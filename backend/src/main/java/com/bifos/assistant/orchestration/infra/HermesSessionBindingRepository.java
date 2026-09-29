package com.bifos.assistant.orchestration.infra;

import com.bifos.assistant.orchestration.domain.HermesSessionBinding;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HermesSessionBindingRepository extends JpaRepository<HermesSessionBinding, Long> {

    /** {@code (profile_name, session_id)} 유일 제약을 탄다. */
    Optional<HermesSessionBinding> findByProfileNameAndSessionId(String profileName, String sessionId);
}
