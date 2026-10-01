package com.bifos.assistant.chat.application;

import com.bifos.assistant.chat.domain.type.ModelTier;
import java.util.List;

/** 현재 요청자가 볼 수 있는 단계와 기본값이다. */
public record ModelTierOptions(List<Tier> tiers, ModelTier userDefaultTier, ModelTier groupDefaultTier, boolean admin) {

    public record Tier(ModelTier tier, String label, String provider, String model, String reasoningEffort) {}
}
