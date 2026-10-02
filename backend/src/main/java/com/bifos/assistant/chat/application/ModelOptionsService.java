package com.bifos.assistant.chat.application;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.chat.domain.ModelChoice;
import com.bifos.assistant.hermes.HermesModelClient;
import com.bifos.assistant.hermes.dto.HermesModelCatalog;
import com.bifos.assistant.hermes.dto.ReasoningCapability;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
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
 * <p>목록은 저장하지 않고 profile 마다 메모리에 {@code ttl} 동안 들고 있는다. 이 목록은 실행 직전의 숨김
 * 판정에도 쓰인다. 시간이 지나 다시 읽다가 실패하면 실패의 종류와 관계없이 들고 있던 옛 목록을 돌려주고,
 * 다음 다시 읽기를 {@link #RETRY_DELAY} 만큼 미룬다. 그 profile 의 목록을 한 번도 읽지 못했다면 읽기의
 * 예외가 그대로 나간다. Hermes 가 답하지 않을 때는 {@code HERMES_UNAVAILABLE} 이다. 동시에 여러 요청이 와도
 * Hermes 를 한 번만 부르도록 막지 않는다. 가족 몇 명이 쓰는 규모라 같은 조회가 겹쳐도 비용이 작다.
 */
@Service
@Slf4j
public class ModelOptionsService {

    /**
     * 다시 읽기가 실패한 뒤 다음 다시 읽기까지 기다리는 시간이다.
     *
     * <p>미루지 않으면 Hermes 가 답하지 않는 동안 실행마다 목록 읽기의 timeout 까지 기다린다. 길게 잡으면 Hermes 가
     * 돌아온 뒤에도 옛 목록으로 판정하는 시간이 늘어나므로 1분으로 둔다.
     */
    private static final Duration RETRY_DELAY = Duration.ofMinutes(1);

    private final AgentService agents;
    private final HermesModelClient hermes;
    private final ModelVisibilityService visibility;
    private final Duration ttl;
    private final Clock clock;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    /** 다음에 다시 읽을 시각과 마지막으로 읽은 목록이다. */
    private record Cached(Instant rereadAt, HermesModelCatalog catalog) {}

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
        if (cached != null && now.isBefore(cached.rereadAt())) {
            return cached.catalog();
        }
        try {
            HermesModelCatalog fresh = hermes.readCatalog(agent.apiBaseUrl(), profile);
            cache.put(profile, new Cached(now.plus(ttl), fresh));
            return fresh;
        } catch (ApiException ex) {
            if (cached == null) {
                throw ex;
            }
            log.warn("모델 목록을 다시 읽지 못해 들고 있던 목록을 돌려준다 profile={} code={}", profile, ex.code());
            // 목록은 그대로 두고 다시 읽을 시각만 미룬다.
            cache.put(profile, new Cached(now.plus(RETRY_DELAY), cached.catalog()));
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
                providers.add(visible);
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
        // 숨긴 모델의 항목은 표에서 뺀다. 남은 모델의 항목만 그대로 옮긴다.
        Map<String, ReasoningCapability> reasoning = new LinkedHashMap<>();
        for (String model : models) {
            ReasoningCapability capability = provider.reasoning().get(model);
            if (capability != null) {
                reasoning.put(model, capability);
            }
        }
        return new HermesModelCatalog.Provider(
                provider.slug(), provider.name(), models, Collections.unmodifiableMap(reasoning));
    }
}
