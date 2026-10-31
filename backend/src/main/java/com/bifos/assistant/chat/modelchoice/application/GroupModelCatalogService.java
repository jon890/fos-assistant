package com.bifos.assistant.chat.modelchoice.application;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.chat.modelchoice.application.dto.GroupModelCatalog;
import com.bifos.assistant.chat.modelchoice.application.dto.ModelOptions;
import com.bifos.assistant.hermes.application.dto.HermesModelCatalog;
import com.bifos.assistant.hermes.application.dto.ReasoningCapability;
import com.bifos.assistant.model.domain.ModelChoice;
import com.bifos.assistant.shared.error.ApiException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 그룹 하나의 모델 목록을 만든다(ADR-20261101 / model-setting).
 *
 * <p>켜진 에이전트의 profile 목록에 모두 있는 provider 와 모델만 남긴다. 관리자가 이 목록에서 고른 모델은 어느
 * 에이전트에서도 돈다. profile 마다 인증된 provider 가 달라 목록이 다르므로 합집합으로 두면 어떤 에이전트에서는
 * 실행이 실패한다. 그룹이 숨긴 모델은 각 목록에서 이미 빠진 채 온다.
 *
 * <p>profile 하나라도 목록을 읽지 못하면 그룹 목록을 만들지 않는다. 읽은 것만으로 교집합을 만들면 읽지 못한
 * profile 에서 못 도는 모델이 섞인다. 각 profile 의 목록은 {@link ModelOptionsService} 가 들고 있는 것을 쓰므로
 * 한 번이라도 읽은 profile 은 Hermes 가 답하지 않아도 옛 목록으로 계산한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GroupModelCatalogService {

    private final AgentService agents;
    private final ModelOptionsService modelOptions;


    /**
     * 그룹의 모델 목록이다. 켜진 에이전트가 없으면 빈 목록이다.
     *
     * @throws ApiException 한 profile 이라도 목록을 한 번도 읽지 못했을 때. 그 profile 의 읽기 예외가 그대로 나간다.
     * Hermes 가 답하지 않으면 {@code HERMES_UNAVAILABLE} 이다
     */
    public GroupModelCatalog catalogFor(Long groupId) {
        return catalogFor(groupId, false);
    }


    /**
     * 들고 있던 목록을 건너뛰고 각 profile 의 목록을 다시 읽어 만든다. 들고 있던 목록에 없는 모델을 저장하려 할 때
     * 쓴다. Hermes 의 대체 목록에는 최근 모델이 빠진다.
     *
     * @throws ApiException {@link #catalogFor(Long)} 와 같다
     */
    public GroupModelCatalog refreshedCatalogFor(Long groupId) {
        return catalogFor(groupId, true);
    }


    private GroupModelCatalog catalogFor(Long groupId, boolean refresh) {
        List<List<HermesModelCatalog.Provider>> lists = new ArrayList<>();
        Set<String> profiles = new LinkedHashSet<>();
        for (Agent agent : agents.enabledAgents()) {
            // 같은 profile 을 가리키는 에이전트는 같은 목록이다. 한 번만 센다.
            if (profiles.add(agent.hermesProfile())) {
                ModelOptions options = refresh
                    ? modelOptions.refreshedOptionsForAgent(groupId, agent)
                    : modelOptions.optionsForAgent(groupId, agent);
                lists.add(options.providers());
            }
        }
        return new GroupModelCatalog(intersect(lists), ModelChoice.REASONING_EFFORTS);
    }


    /**
     * 관리자가 숨길 모델을 고르는 후보다. 켜진 에이전트 profile 의 숨기지 않은 목록과 숨긴 것까지 모두 담은 합집합이다.
     *
     * <p>고르는 목록(교집합)과 달리 합집합인 까닭은 숨김이 어느 profile 에서든 걸려야 해서다. 교집합으로 두면 한
     * profile 에만 있는 모델은 숨길 방법이 없고, 그 profile 의 에이전트는 숨길 모델로 계속 돈다. 숨김을 적용하지 않은
     * 목록으로 만들어, 이미 숨긴 모델도 후보에 남아 풀 수 있다.
     *
     * @throws ApiException 한 profile 이라도 목록을 한 번도 읽지 못했을 때. 읽은 것만으로 만들면 읽지 못한 profile 에만
     * 있는 모델이 빠진다
     */
    public GroupModelCatalog hideCandidates() {
        List<List<HermesModelCatalog.Provider>> lists = new ArrayList<>();
        Set<String> profiles = new LinkedHashSet<>();
        for (Agent agent : agents.enabledAgents()) {
            if (profiles.add(agent.hermesProfile())) {
                lists.add(modelOptions.unfilteredForAgent(agent).providers());
            }
        }
        return new GroupModelCatalog(union(lists), ModelChoice.REASONING_EFFORTS);
    }


    /** 첫 목록의 차례를 지키며 나머지 목록에만 있는 provider 와 모델을 뒤에 붙인다. */
    static List<HermesModelCatalog.Provider> union(List<List<HermesModelCatalog.Provider>> lists) {
        Map<String, List<HermesModelCatalog.Provider>> bySlug = new LinkedHashMap<>();
        for (List<HermesModelCatalog.Provider> list : lists) {
            for (HermesModelCatalog.Provider provider : list) {
                bySlug.computeIfAbsent(provider.slug(), slug -> new ArrayList<>()).add(provider);
            }
        }
        List<HermesModelCatalog.Provider> result = new ArrayList<>();
        for (List<HermesModelCatalog.Provider> same : bySlug.values()) {
            LinkedHashSet<String> models = new LinkedHashSet<>();
            same.forEach(provider -> models.addAll(provider.models()));
            Map<String, ReasoningCapability> reasoning = new LinkedHashMap<>();
            for (String model : models) {
                reasoning.put(model, agreed(same.stream()
                    .filter(provider -> provider.models().contains(model))
                    .map(provider -> provider.reasoning().getOrDefault(model, ReasoningCapability.UNKNOWN_ALL))
                    .toList()));
            }
            HermesModelCatalog.Provider first = same.getFirst();
            result.add(new HermesModelCatalog.Provider(
                first.slug(), first.name(), List.copyOf(models), Collections.unmodifiableMap(reasoning)));
        }
        return List.copyOf(result);
    }


    /** 첫 목록의 차례를 지키며 모든 목록에 있는 provider 와 모델만 남긴다. */
    static List<HermesModelCatalog.Provider> intersect(List<List<HermesModelCatalog.Provider>> lists) {
        if (lists.isEmpty()) {
            return List.of();
        }
        List<HermesModelCatalog.Provider> result = new ArrayList<>();
        for (HermesModelCatalog.Provider first : lists.getFirst()) {
            List<HermesModelCatalog.Provider> same = lists.stream()
                .map(list -> list.stream()
                    .filter(provider -> provider.slug().equals(first.slug()))
                    .findFirst()
                    .orElse(null))
                .toList();
            if (same.contains(null)) {
                continue;
            }
            List<String> models = first.models().stream()
                .filter(model -> same.stream().allMatch(provider -> provider.models().contains(model)))
                .toList();
            if (models.isEmpty()) {
                continue;
            }
            Map<String, ReasoningCapability> reasoning = new LinkedHashMap<>();
            for (String model : models) {
                reasoning.put(model, agreed(same.stream()
                    .map(provider -> provider.reasoning().getOrDefault(model, ReasoningCapability.UNKNOWN_ALL))
                    .toList()));
            }
            result.add(new HermesModelCatalog.Provider(
                first.slug(), first.name(), models, Collections.unmodifiableMap(reasoning)));
        }
        return List.copyOf(result);
    }


    /** profile 마다 밝힌 지원이 다르면 그 칸은 모른다고 둔다. 확인한 것만 보인다(ADR-060). */
    private static ReasoningCapability agreed(List<ReasoningCapability> capabilities) {
        ReasoningCapability first = capabilities.getFirst();
        boolean sameSupport = capabilities.stream().allMatch(it -> Objects.equals(it.support(), first.support()));
        boolean sameDisable = capabilities.stream().allMatch(it -> Objects.equals(it.disable(), first.disable()));
        return new ReasoningCapability(
            sameSupport ? first.support() : ReasoningCapability.Support.UNKNOWN,
            sameDisable ? first.disable() : ReasoningCapability.Support.UNKNOWN);
    }
}
