package com.bifos.assistant.chat.modelchoice.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;

/** 그룹과 설정 종류로 이루어진 그룹 설정의 key 다. */
@Embeddable
public record GroupSettingId(
    @Column(name = "group_id", nullable = false) Long groupId,

    @Column(name = "setting_key", nullable = false, length = 32) String settingKey)
    implements
        Serializable {
}
