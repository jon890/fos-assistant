package com.bifos.assistant.chat.application;

import com.bifos.assistant.hermes.dto.HermesModelCatalog;
import com.bifos.assistant.hermes.dto.ReasoningCapability;
import com.bifos.assistant.model.domain.ModelChoice;
import java.util.List;

/**
 * 한 에이전트의 profile 로 대화가 고를 수 있는 모델이다.
 *
 * @param defaultProvider 대화가 고르지 않았을 때 도는 provider. 에이전트 기본값이 있으면 그 값이고, 없으면
 *     profile 의 값이다. Hermes 가 주지 않으면 null
 * @param defaultModel 대화가 고르지 않았을 때 도는 모델. 정하는 차례는 {@code defaultProvider} 와 같다
 * @param defaultReasoningEffort 에이전트 기본 effort. 정하지 않았으면 null 이고 profile 의 값으로 돈다
 * @param defaultFromAgent 기본 모델을 에이전트 기본값이 정했는가. 거짓이면 profile 의 값이다
 * @param defaultAvailable 기본 모델이 {@code providers} 에 있는가. 숨겼거나 목록에서 빠졌으면 거짓이다.
 *     기본 모델을 모르면 참으로 본다
 * @param providers 고를 수 있는 provider. 기본 provider 가 있으면 맨 앞이고, 나머지는 Hermes 가 준 차례다.
 *     각 provider 의 reasoning 표에 항목이 없는 모델은 {@link ReasoningCapability#UNKNOWN_ALL} 로 읽는다
 * @param reasoningEfforts 고를 수 있는 effort. 낮은 것부터 적는다
 */
public record ModelOptions(
        String defaultProvider,
        String defaultModel,
        String defaultReasoningEffort,
        boolean defaultFromAgent,
        boolean defaultAvailable,
        List<HermesModelCatalog.Provider> providers,
        List<String> reasoningEfforts) {

    /** profile 의 값만으로 만든다. 에이전트 기본값이 없는 목록이다. */
    public ModelOptions(
            String defaultProvider,
            String defaultModel,
            List<HermesModelCatalog.Provider> providers,
            List<String> reasoningEfforts) {
        this(defaultProvider, defaultModel, null, false, true, providers, reasoningEfforts);
    }

    /** 그 provider 의 그 모델을 고를 수 있는가. */
    public boolean offers(String provider, String model) {
        return providers.stream()
                .anyMatch(row -> row.slug().equals(provider) && row.models().contains(model));
    }

    /**
     * 그 모델에서 그 effort 를 고를 수 있는가. {@code none} 만 지원을 확인해야 한다(ADR-060).
     *
     * <p>{@code none} 은 그 모델의 끄기 지원이 {@code SUPPORTED} 이고 reasoning 지원이 {@code UNSUPPORTED} 가 아닐 때만
     * 참이다. 모델을 비웠으면 기본 모델로 판정하고, 기본 모델도 모르거나 목록에 없는 모델이면 거짓이다.
     *
     * @param provider null 이면 그 모델을 가진 첫 provider 로 본다
     * @param model null 이면 {@code defaultProvider}, {@code defaultModel} 로 본다
     */
    public boolean allowsEffort(String provider, String model, String effort) {
        if (!ModelChoice.EFFORT_NONE.equals(effort)) {
            return true;
        }
        String targetProvider = provider;
        String targetModel = model;
        if (targetModel == null) {
            targetProvider = defaultProvider;
            targetModel = defaultModel;
        }
        if (targetModel == null) {
            return false;
        }
        String wantedProvider = targetProvider;
        String wantedModel = targetModel;
        return providers.stream()
                .filter(row -> (wantedProvider == null || row.slug().equals(wantedProvider))
                        && row.models().contains(wantedModel))
                .findFirst()
                .map(row -> row.reasoning().getOrDefault(wantedModel, ReasoningCapability.UNKNOWN_ALL))
                .map(capability -> capability.disable() == ReasoningCapability.Support.SUPPORTED
                        && capability.support() != ReasoningCapability.Support.UNSUPPORTED)
                .orElse(false);
    }
}
