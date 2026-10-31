package com.bifos.assistant.chat.modelchoice.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

/**
 * 그룹 설정 한 줄이다. 그룹과 설정 종류마다 JSON 하나를 둔다(ADR-20261101 / model-setting).
 *
 * <p>값의 형식은 설정 종류를 쓰는 쪽이 저장할 때 검사한다. 이 엔티티는 문자열만 갖는다.
 */
@Entity
@Table(name = "group_setting")
@Getter
@Accessors(fluent = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GroupSetting {

    /** 단계별 모델과 그룹 기본 단계, 숨김 목록을 담는 설정 종류다. */
    public static final String MODEL = "MODEL";

    @EmbeddedId
    private GroupSettingId id;

    /** 설정 JSON 문자열이다. MySQL {@code JSON} 타입이 아니라 문자열로 둔다. */
    @Column(name = "setting_value", nullable = false, columnDefinition = "TEXT")
    private String settingValue;

    /** 마지막으로 바꾼 시각이다. 옮겨 온 줄은 바꾼 시각을 몰라 비어 있다. */
    @Column(name = "updated_at")
    private Instant updatedAt;


    private GroupSetting(Long groupId, String settingKey, String settingValue, Instant updatedAt) {
        this.id = new GroupSettingId(groupId, settingKey);
        this.settingValue = settingValue;
        this.updatedAt = updatedAt;
    }


    public static GroupSetting of(Long groupId, String settingKey, String settingValue, Instant updatedAt) {
        return new GroupSetting(groupId, settingKey, settingValue, updatedAt);
    }


    public void change(String settingValue, Instant updatedAt) {
        this.settingValue = settingValue;
        this.updatedAt = updatedAt;
    }
}
