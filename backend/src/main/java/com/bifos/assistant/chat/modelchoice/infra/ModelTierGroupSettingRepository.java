package com.bifos.assistant.chat.modelchoice.infra;

import com.bifos.assistant.chat.modelchoice.domain.ModelTierGroupSetting;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ModelTierGroupSettingRepository extends JpaRepository<ModelTierGroupSetting, Long> {
}
