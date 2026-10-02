package com.bifos.assistant.chat.application;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.chat.domain.ModelChoice;
import com.bifos.assistant.hermes.HermesModelClient;
import com.bifos.assistant.hermes.dto.HermesModelCatalog;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 대화가 고를 수 있는 모델 목록을 Hermes 에 물어 돌려준다.
 *
 * <p>목록은 저장하지 않고 profile 마다 메모리에 {@code ttl} 동안 들고 있는다. 시간이 지나 다시 읽다가
 * Hermes 가 답하지 못하면 들고 있던 옛 목록을 돌려준다. 그 profile 의 목록을 한 번도 읽지 못했다면
 * {@code HERMES_UNAVAILABLE} 이다. 동시에 여러 요청이 와도 Hermes 를 한 번만 부르도록 막지 않는다.
 * 가족 몇 명이 쓰는 규모라 같은 조회가 겹쳐도 비용이 작다.
 */
@Service
@Slf4j
public class ModelOptionsService {

    private final AgentService agents;
    private final HermesModelClient hermes;
    private final ModelVisibilityService visibility;
    private final Duration ttl;
    private final Clock clock;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    /** 읽은 시각과 그때 읽은 목록이다. */
    private record Cached(Instant readAt, HermesModelCatalog catalog) {}

    @Autowired
    public ModelOptionsService(
            AgentService agents,
            HermesModelClient hermes,
            ModelVisibilityService visibility,
            @Value("${assistant.chat.model-options-ttl:10m}") Duration ttl) {
        this(agents, hermes, visibility, ttl, Clock.systemUTC());
    }

    public ModelOptionsService(
            AgentService agents,
            HermesModelClient hermes,
            ModelVisibilityService visibility,
            Duration ttl,
            Clock clock) {
        this.agents = agents;
        this.hermes = hermes;
        this.visibility = visibility;
        this.ttl = ttl;
        this.clock = clock;
    }

    /** 요청자가 쓸 수 있는 에이전트의 profile 로 고를 수 있는 모델을 돌려준다. */
    public ModelOptions optionsFor(CurrentUser user, String agentCode) {
        Agent agent = agents.requireStartable(user, agentCode);
        return optionsForAgent(user.groupId(), agent);
    }

    /**
     * 이미 권한을 확인한 에이전트의 profile 모델 목록을 읽는다.
     *
     * <p>그 그룹이 숨긴 provider 와 모델은 뺀다. 기본 모델은 에이전트 기본값이 있으면 그 값이다(ADR-054).
     */
    public ModelOptions optionsForAgent(Long groupId, Agent agent) {
        return optionsOf(catalogFor(agent), agent, visibility.hiddenFor(groupId));
    }

    /**
     * 숨김을 적용하지 않은 목록을 읽는다. 관리자가 무엇을 숨길지 고르는 화면이 쓴다.
     *
     * <p>기본 모델도 에이전트 기본값을 얹지 않은 profile 의 값이다.
     */
    public ModelOptions unfilteredForAgent(Agent agent) {
        return optionsOf(catalogFor(agent), null, HiddenModels.none());
    }

    /** 그 profile 의 기본 provider 와 모델을 들고 있는 목록에서 읽는다. Hermes 가 주지 않은 값은 null 이다. */
    public ModelChoice profileDefaultOf(Agent agent) {
        HermesModelCatalog catalog = catalogFor(agent);
        return ModelChoice.stored(catalog.defaultProvider(), catalog.defaultModel(), null);
    }

    private HermesModelCatalog catalogFor(Agent agent) {
        String profile = agent.hermesProfile();
        Instant now = clock.instant();
        Cached cached = cache.get(profile);
        if (cached != null && now.isBefore(cached.readAt().plus(ttl))) {
            return cached.catalog();
        }
        try {
            HermesModelCatalog fresh = hermes.readCatalog(agent.apiBaseUrl(), profile);
            cache.put(profile, new Cached(now, fresh));
            return fresh;
        } catch (ApiException ex) {
            if (cached == null || ex.code() != ErrorCode.HERMES_UNAVAILABLE) {
                throw ex;
            }
            log.warn("모델 목록을 다시 읽지 못해 들고 있던 목록을 돌려준다 profile={}", profile);
            return cached.catalog();
        }
    }

    /**
     * @param agent 기본값을 얹을 에이전트. null 이면 profile 의 값만 쓴다
     */
    private static ModelOptions optionsOf(HermesModelCatalog catalog, Agent agent, HiddenModels hidden) {
        List<HermesModelCatalog.Provider> providers = new ArrayList<>();
        for (HermesModelCatalog.Provider provider : catalog.providers()) {
            HermesModelCatalog.Provider visible = withoutHidden(provider, hidden);
            if (visible != null) {
                providers.add(withEveryModelReasoning(visible));
            }
        }
        boolean fromAgent = agent != null && agent.defaultModel() != null;
        String defaultProvider = fromAgent ? agent.defaultModelProvider() : catalog.defaultProvider();
        String defaultModel = fromAgent ? agent.defaultModel() : catalog.defaultModel();
        // 기본 provider 를 맨 앞에 둔다. 나머지는 Hermes 가 준 차례를 지킨다.
        providers.sort((left, right) -> Boolean.compare(
                !Objects.equals(left.slug(), defaultProvider), !Objects.equals(right.slug(), defaultProvider)));
        // Hermes 가 기본 provider 를 주지 않으면 모델 이름만으로 본다. 없는 값과 견주면 쓸 수 있는 기본 모델도 거짓이 된다.
        boolean available = defaultModel == null
                || providers.stream()
                        .anyMatch(provider ->
                                (defaultProvider == null || provider.slug().equals(defaultProvider))
                                        && provider.models().contains(defaultModel));
        return new ModelOptions(
                defaultProvider,
                defaultModel,
                agent == null ? null : agent.defaultReasoningEffort(),
                fromAgent,
                available,
                List.copyOf(providers),
                ModelChoice.REASONING_EFFORTS);
    }

    /** 숨긴 모델을 뺀 provider 다. provider 전체를 숨겼거나 남는 모델이 없으면 null 이다. */
    private static HermesModelCatalog.Provider withoutHidden(
            HermesModelCatalog.Provider provider, HiddenModels hidden) {
        if (hidden.hidesProvider(provider.slug())) {
            return null;
        }
        List<String> models = provider.models().stream()
                .filter(model -> !hidden.hides(provider.slug(), model))
                .toList();
        if (models.isEmpty()) {
            return null;
        }
        return new HermesModelCatalog.Provider(provider.slug(), provider.name(), models, provider.reasoning());
    }

    /**
     * reasoning 지원 표에 그 provider 의 모델을 모두 넣는다.
     *
     * <p>Hermes 가 밝히지 않은 모델은 참으로 본다. 모르는 모델에서 effort 를 막으면 고를 수 있는 것을 못
     * 고르게 된다. 화면이 표에 없는 모델을 따로 판단하지 않도록 여기서 채운다.
     */
    private static HermesModelCatalog.Provider withEveryModelReasoning(HermesModelCatalog.Provider provider) {
        Map<String, Boolean> reasoning = new LinkedHashMap<>();
        for (String model : provider.models()) {
            reasoning.put(model, provider.reasoning().getOrDefault(model, true));
        }
        return new HermesModelCatalog.Provider(
                provider.slug(), provider.name(), provider.models(), Collections.unmodifiableMap(reasoning));
    }
}
