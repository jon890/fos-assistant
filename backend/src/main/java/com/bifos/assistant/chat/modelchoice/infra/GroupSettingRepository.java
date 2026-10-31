package com.bifos.assistant.chat.modelchoice.infra;

import com.bifos.assistant.chat.modelchoice.domain.GroupSetting;
import com.bifos.assistant.chat.modelchoice.domain.GroupSettingId;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GroupSettingRepository extends JpaRepository<GroupSetting, GroupSettingId> {

    /** 한 설정의 일부만 바꾸는 저장이 다른 저장의 변경을 덮지 않도록 잠그고 읽는다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from GroupSetting s where s.id = :id")
    Optional<GroupSetting> findForUpdate(@Param("id") GroupSettingId id);
}
