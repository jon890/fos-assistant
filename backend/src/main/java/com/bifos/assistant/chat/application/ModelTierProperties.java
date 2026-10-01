package com.bifos.assistant.chat.application;

import com.bifos.assistant.chat.domain.ModelChoice;
import com.bifos.assistant.chat.domain.type.ModelTier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** 그룹이 저장한 단계 정의가 없을 때 쓰는 배포 초기 설정이다. */
@Validated
@ConfigurationProperties(prefix = "assistant.model-tiers")
public record ModelTierProperties(Tier fast, Tier balanced, Tier deep) {

    public ModelTierProperties {
        fast = fast == null ? Tier.empty() : fast;
        balanced = balanced == null ? Tier.empty() : balanced;
        deep = deep == null ? Tier.empty() : deep;
    }

    public Tier forTier(ModelTier tier) {
        return switch (tier) {
            case FAST -> fast;
            case BALANCED -> balanced;
            case DEEP -> deep;
        };
    }

    /** 한 단계의 모델 제공사, 모델과 리즈닝 강도 설정이다. */
    public record Tier(String provider, String model, String reasoningEffort) {

        public Tier {
            provider = blankToNull(provider);
            model = blankToNull(model);
            reasoningEffort = blankToNull(reasoningEffort);
            boolean missingModel = model == null;
            boolean missingReasoningEffort = reasoningEffort == null;
            if (missingModel != missingReasoningEffort || provider != null && missingModel) {
                throw new IllegalStateException(
                        "each assistant.model-tiers mapping needs both model and reasoning-effort");
            }
            if (provider != null && provider.length() > ModelChoice.PROVIDER_MAX_LENGTH) {
                throw new IllegalStateException("assistant.model-tiers provider is too long");
            }
            if (model != null && model.length() > ModelChoice.MODEL_MAX_LENGTH) {
                throw new IllegalStateException("assistant.model-tiers model is too long");
            }
            if (reasoningEffort != null && !ModelChoice.REASONING_EFFORTS.contains(reasoningEffort)) {
                throw new IllegalStateException("assistant.model-tiers reasoning-effort is invalid");
            }
        }

        private static Tier empty() {
            return new Tier(null, null, null);
        }

        private static String blankToNull(String value) {
            if (value == null) {
                return null;
            }
            String stripped = value.strip();
            return stripped.isEmpty() ? null : stripped;
        }
    }
}
