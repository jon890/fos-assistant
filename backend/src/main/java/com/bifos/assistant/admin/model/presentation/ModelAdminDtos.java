package com.bifos.assistant.admin.model.presentation;

import com.bifos.assistant.chat.application.AgentModelSettings;
import com.bifos.assistant.chat.application.HiddenModels;
import com.bifos.assistant.chat.application.ModelOptions;
import com.bifos.assistant.hermes.dto.HermesModelCatalog;
import com.bifos.assistant.hermes.dto.ReasoningCapability;
import com.bifos.assistant.model.domain.ModelChoice;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 관리자 모델 설정 경로가 주고받는 모양이다. 사용자 경로와 계약이 따로 바뀔 수 있어 겹치는 record 는 {@code ChatDtos} 에서 복사해 둔다. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ModelAdminDtos {

    /** 에이전트에 저장된 기본 모델이다. 세 값이 모두 null 이면 profile 의 값으로 돈다. */
    public record AgentModelDefaultView(String provider, String model, String reasoningEffort) {

        static AgentModelDefaultView from(ModelChoice choice) {
            return new AgentModelDefaultView(choice.provider(), choice.model(), choice.reasoningEffort());
        }
    }

    /**
     * 관리자가 에이전트 기본 모델과 숨김을 정할 때 보는 값이다.
     * @param agentDefault 에이전트에 저장된 기본값
     * @param catalog 숨김을 적용하지 않은 목록. 기본 provider 와 기본 모델은 profile 의 값이다. Hermes 가 목록을
     * 답하지 못했으면 null 이다
     * @param hidden 그룹의 숨김 목록
     */
    public record AgentModelSettingsView(
        AgentModelDefaultView agentDefault, ModelOptionsView catalog, HiddenModelsView hidden) {

        static AgentModelSettingsView from(AgentModelSettings settings) {
            return new AgentModelSettingsView(
                AgentModelDefaultView.from(settings.agentDefault()),
                settings.catalog() == null ? null : ModelOptionsView.from(settings.catalog()),
                HiddenModelsView.from(settings.hidden()));
        }
    }

    /** 그룹의 숨김 목록 전체다. 저장할 때는 이 목록으로 통째로 바꾼다. */
    public record HiddenModelsView(@NotNull List<@NotNull @Valid HiddenModelEntry> entries) {

        static HiddenModelsView from(HiddenModels hidden) {
            return new HiddenModelsView(hidden.entries().stream()
                .map(entry -> new HiddenModelEntry(entry.provider(), entry.model()))
                .toList());
        }


        List<HiddenModels.Entry> toEntries() {
            return entries.stream()
                .map(entry -> new HiddenModels.Entry(entry.provider(), entry.model()))
                .toList();
        }
    }

    /**
     * 숨긴 provider 또는 모델 하나다.
     *
     * @param model null 이면 그 provider 전체다
     */
    public record HiddenModelEntry(@NotNull String provider, String model) {
    }

    /** 대화에서 쓸 모델과 effort 다. 모두 비워 보내면 그 profile 의 기본값으로 돌아간다. */
    public record ChooseModelRequest(String provider, String model, String reasoningEffort) {

        /** 요청 값을 검증해 선택으로 바꾼다. 맞지 않으면 {@code VALIDATION_FAILED} 다. */
        public ModelChoice toChoice() {
            return ModelChoice.of(provider, model, reasoningEffort);
        }
    }

    /**
     * 그 에이전트의 profile 로 고를 수 있는 모델이다. 그룹이 숨긴 것은 빠져 있다.
     * @param defaultModel 고르지 않았을 때 도는 모델. 에이전트 기본값이 있으면 그 값이고 없으면 profile 의 값이다
     * @param defaultReasoningEffort 에이전트 기본 effort. 정하지 않았으면 null
     * @param defaultFromAgent 기본 모델을 에이전트 기본값이 정했는가
     * @param defaultAvailable 기본 모델이 {@code providers} 에 있는가. 숨겼거나 목록에서 빠졌으면 거짓이다
     */
    public record ModelOptionsView(
        String defaultProvider,
        String defaultModel,
        String defaultReasoningEffort,
        boolean defaultFromAgent,
        boolean defaultAvailable,
        List<ProviderView> providers,
        List<String> reasoningEfforts) {

        static ModelOptionsView from(ModelOptions options) {
            return new ModelOptionsView(
                options.defaultProvider(),
                options.defaultModel(),
                options.defaultReasoningEffort(),
                options.defaultFromAgent(),
                options.defaultAvailable(),
                options.providers().stream().map(ProviderView::from).toList(),
                options.reasoningEfforts());
        }
    }

    /**
     * @param provider Hermes 가 부르는 provider 이름
     * @param name 화면에 보일 이름
     * @param models 그 provider 의 모델 이름
     * @param reasoning 모델 이름을 열쇠로 한 reasoning 지원과 끄기 지원. 모든 모델이 표에 있고, Hermes 가 밝히지 않은
     * 칸은 {@code UNKNOWN} 이다
     * @param reasoningCapable 옛 web 호환용이고 다음 배포에서 지운다. 각 모델의 {@code support} 가
     * {@code UNSUPPORTED} 가 아니면 참이다
     */
    public record ProviderView(
        String provider,
        String name,
        List<String> models,
        Map<String, ReasoningCapability> reasoning,
        Map<String, Boolean> reasoningCapable) {

        static ProviderView from(HermesModelCatalog.Provider provider) {
            Map<String, ReasoningCapability> reasoning = new LinkedHashMap<>();
            Map<String, Boolean> reasoningCapable = new LinkedHashMap<>();
            for (String model : provider.models()) {
                ReasoningCapability capability = provider.reasoning().getOrDefault(model, ReasoningCapability.UNKNOWN_ALL);
                reasoning.put(model, capability);
                reasoningCapable.put(model, capability.support() != ReasoningCapability.Support.UNSUPPORTED);
            }
            return new ProviderView(
                provider.slug(),
                provider.name(),
                provider.models(),
                Collections.unmodifiableMap(reasoning),
                Collections.unmodifiableMap(reasoningCapable));
        }
    }
}
