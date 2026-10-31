package com.bifos.assistant.admin.model.presentation;

import com.bifos.assistant.chat.modelchoice.application.dto.GroupModelCatalog;
import com.bifos.assistant.chat.modelchoice.application.dto.HiddenModels;
import com.bifos.assistant.hermes.application.dto.HermesModelCatalog;
import com.bifos.assistant.hermes.application.dto.ReasoningCapability;
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

    /**
     * 그룹 하나의 모델 목록이다. 단계별 모델을 고르는 목록과 숨길 모델을 고르는 후보가 같은 모양을 쓴다.
     *
     * @param catalog 단계별 모델이면 켜진 모든 에이전트에서 도는 provider 와 모델이고, 숨길 후보면 어느 profile 에든 있는
     * provider 와 모델이다. 목록을 읽지 못했으면 null 이다
     */
    public record GroupModelCatalogView(CatalogView catalog) {

        static GroupModelCatalogView from(GroupModelCatalog catalog) {
            return new GroupModelCatalogView(new CatalogView(
                catalog.providers().stream().map(ProviderView::from).toList(), catalog.reasoningEfforts()));
        }


        static GroupModelCatalogView missing() {
            return new GroupModelCatalogView(null);
        }
    }

    /** 그룹 목록의 provider 와 effort 선택지다. */
    public record CatalogView(List<ProviderView> providers, List<String> reasoningEfforts) {
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
