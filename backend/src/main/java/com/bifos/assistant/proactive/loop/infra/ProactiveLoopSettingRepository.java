package com.bifos.assistant.proactive.loop.infra;

import com.bifos.assistant.proactive.loop.domain.ProactiveLoopSetting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProactiveLoopSettingRepository extends JpaRepository<ProactiveLoopSetting, Long> {

    // 읽는 메서드는 없다. 켜짐과 쉬기는 CHECK 작업이 갖는다(LoopScheduleSettings). 이 표는 순서 7 에서 지울 때까지 에이전트 정리만 쓴다.

    /** 지운 에이전트를 정리할 때 그 에이전트의 설정 줄을 모두 지운다. 지운 행 수를 돌려준다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from ProactiveLoopSetting s where s.agentId = :agentId")
    int deleteAllOfAgent(@Param("agentId") Long agentId);
}
