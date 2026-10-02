package com.bifos.assistant.chat.domain;

import com.bifos.assistant.model.domain.type.ModelTier;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

/** 그룹 관리자가 정한 단계 하나다. 세 mapping 값이 비면 profile 기본값으로 돈다. */
@Entity
@Table(name = "model_tier_definition", uniqueConstraints = @UniqueConstraint(columnNames = {"group_id", "tier"}))
@Getter
@Accessors(fluent = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ModelTierDefinition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "group_id", nullable = false)
    private Long groupId;

    @Enumerated(EnumType.STRING)
    @Column(name = "tier", nullable = false, length = 16)
    private ModelTier tier;

    @Column(name = "provider", length = 64)
    private String provider;

    @Column(name = "model", length = 128)
    private String model;

    @Column(name = "reasoning_effort", length = 16)
    private String reasoningEffort;

    private ModelTierDefinition(Long groupId, ModelTier tier, String provider, String model, String reasoningEffort) {
        this.groupId = groupId;
        this.tier = tier;
        this.provider = provider;
        this.model = model;
        this.reasoningEffort = reasoningEffort;
    }

    public static ModelTierDefinition of(
            Long groupId, ModelTier tier, String provider, String model, String reasoningEffort) {
        return new ModelTierDefinition(groupId, tier, provider, model, reasoningEffort);
    }
}
