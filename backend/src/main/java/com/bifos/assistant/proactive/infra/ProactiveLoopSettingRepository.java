package com.bifos.assistant.proactive.infra;

import com.bifos.assistant.proactive.domain.ProactiveLoopSetting;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface ProactiveLoopSettingRepository extends JpaRepository<ProactiveLoopSetting, Long> {

    Optional<ProactiveLoopSetting> findByUserIdAndAgentId(Long userId, Long agentId);

    /** 하루 상한을 세기 전에 그 사용자의 설정 줄을 모두 쓰기 잠금으로 읽는다. 같은 사용자의 시도가 상한을 함께 넘지 않게 한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<ProactiveLoopSetting> findByUserIdOrderByIdAsc(Long userId);
}
