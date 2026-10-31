package com.bifos.assistant.chat.modelchoice.application.dto;

import com.bifos.assistant.model.domain.type.ModelTier;
import java.util.Arrays;
import java.util.List;

/**
 * 그룹의 모델 설정이다. {@code group_setting} 의 {@code MODEL} 줄 하나에 담긴다(ADR-20261101 / model-setting).
 *
 * @param tiers 칸 다섯. {@link ModelTier} 선언 차례이고 칸마다 하나다
 * @param defaultTier 그룹 기본 칸. 없으면 null
 * @param hidden 그룹이 숨긴 provider 와 모델
 */
public record ModelSetting(List<Stop> tiers, ModelTier defaultTier, HiddenModels hidden) {

    /**
     * 칸 하나가 가리키는 값이다. 셋이 모두 null 이면 빈 칸이다.
     *
     * @param provider null 이면 요청한 에이전트의 기본 provider 로 돈다. 옮겨 온 정의에만 있다
     */
    public record Stop(ModelTier tier, String provider, String model, String reasoningEffort) {

        public static Stop empty(ModelTier tier) {
            return new Stop(tier, null, null, null);
        }


        public boolean isEmpty() {
            return provider == null && model == null && reasoningEffort == null;
        }
    }


    public ModelSetting {
        tiers = List.copyOf(tiers);
    }


    /** 줄이 없는 그룹의 설정이다. 모든 칸이 비었다. */
    public static ModelSetting empty() {
        return new ModelSetting(
            Arrays.stream(ModelTier.values()).map(Stop::empty).toList(), null, HiddenModels.none());
    }


    public Stop stop(ModelTier tier) {
        return tiers.get(tier.ordinal());
    }


    public ModelSetting withTiers(List<Stop> nextTiers, ModelTier nextDefault) {
        return new ModelSetting(nextTiers, nextDefault, hidden);
    }


    public ModelSetting withHidden(HiddenModels nextHidden) {
        return new ModelSetting(tiers, defaultTier, nextHidden);
    }
}
