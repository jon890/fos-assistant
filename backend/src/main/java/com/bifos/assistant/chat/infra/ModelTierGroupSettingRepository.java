package com.bifos.assistant.chat.infra;

import com.bifos.assistant.chat.domain.ModelTierGroupSetting;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ModelTierGroupSettingRepository extends JpaRepository<ModelTierGroupSetting, Long> {}
