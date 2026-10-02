package com.bifos.assistant.chat.application;

import com.bifos.assistant.chat.domain.ModelChoice;
import com.bifos.assistant.chat.domain.type.ModelTier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 예전 배포가 환경 변수로 주던 단계 초기값이다.
 *
 * <p>실행할 때는 읽지 않는다. {@link ModelTierSeedImporter} 가 기동할 때 정의 행이 없는 그룹에 한 번 옮긴다.
 * 옮긴 뒤에는 운영 설정에서 지운다(ADR-054).
 */
@Validated
@ConfigurationProperties(prefix = "assistant.model-tiers")
public record ModelTierProperties(Tier fast, Tier balanced, Tier deep) {

    public ModelTierProperties {
        fast = fast == null ? Tier.empty() : fast;
        balanced = balanced == null ? Tier.empty() : balanced;
        deep = deep == null ? Tier.empty() : deep;
    }

    /** 세 단계 가운데 하나라도 모델을 정했는가. */
    public boolean hasAnyMapping() {
        return fast.model() != null || balanced.model() != null || deep.model() != null;
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
