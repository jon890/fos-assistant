package com.bifos.assistant.chat.domain;

import com.bifos.assistant.chat.domain.type.ModelTier;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

/** 그룹 전체에 적용하는 기본 단계다. */
@Entity
@Table(name = "model_tier_group_setting")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Getter
@Accessors(fluent = true)
public class ModelTierGroupSetting {

    @Id
    @Column(name = "group_id")
    private Long groupId;

    @Enumerated(EnumType.STRING)
    @Column(name = "default_tier", length = 16)
    private ModelTier defaultTier;

    private ModelTierGroupSetting(Long groupId, ModelTier defaultTier) {
        this.groupId = groupId;
        this.defaultTier = defaultTier;
    }

    public static ModelTierGroupSetting of(Long groupId, ModelTier defaultTier) {
        return new ModelTierGroupSetting(groupId, defaultTier);
    }
}
