package com.bifos.assistant.chat.modelchoice.application;

import com.bifos.assistant.chat.modelchoice.application.dto.GroupModelCatalog;
import com.bifos.assistant.chat.modelchoice.application.dto.GroupModelTiers;
import com.bifos.assistant.chat.modelchoice.application.dto.HiddenModels;
import com.bifos.assistant.chat.modelchoice.application.dto.ModelOptions;
import com.bifos.assistant.chat.modelchoice.application.dto.ModelSetting;
import com.bifos.assistant.chat.modelchoice.application.dto.ModelTierOptions;
import com.bifos.assistant.chat.modelchoice.application.dto.ResolvedModelTier;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.conversation.domain.type.ModelSelectionMode;
import com.bifos.assistant.model.domain.ModelChoice;
import com.bifos.assistant.model.domain.type.ModelTier;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.usage.execution.domain.InternalValuePolicy;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 그룹 단계와 사용자 기본값, 에이전트 기본 모델을 해석한다.
 *
 * <p>칸은 ChatGPT 의 Thinking 슬라이더 다섯 칸이다(ADR-20261101 / model-setting). 칸 정의는 그룹 설정 줄
 * ({@link ModelSettingStore})에서 읽는다. 줄이 없는 그룹의 다섯 칸은 모두 빈 칸이고, 빈 칸은 에이전트 기본값으로 돈다.
 * 에이전트 기본값도 없으면 요청에 모델을 싣지 않아 Hermes profile 의 값으로 돈다(ADR-054).
 *
 * <p>실행할 때는 단계가 가리키는 모델이 Hermes 목록에 없다는 이유만으로 막지 않는다. Hermes 가 돌려준 목록이
 * 대체 목록일 수 있고(최근 모델이 빠진다), 정말 못 쓰는 모델이면 Hermes 가 그 실행을 거절해 실행 기록에 남는다.
 * 목록에 없으면 캐시를 건너뛰고 한 번 다시 읽어 보고, 그래도 없으면 경고만 남기고 그 값으로 보낸다. 단계를 고르는
 * 입력 검증({@link #resolveTier})은 다시 읽은 뒤에도 없으면 거절한다.
 *
 * <p>실행 직전의 숨김 판정은 profile 의 기본 모델까지 본다. 요청에 모델을 싣지 않는 실행도 그 모델이 그룹이
 * 숨긴 것이면 거절한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ModelTierService {

    private final ModelSettingStore settings;
    private final AppUserRepository users;
    private final ModelOptionsService modelOptions;
    private final ModelVisibilityService visibility;
    private final GroupModelCatalogService groupCatalog;
    private final Clock clock;


    /**
     * 요청자가 고를 수 있는 단계와 기본값이다.
     *
     * <p>단계가 가리키는 provider 와 모델과 effort 는 내부 값이라 {@code ADMIN} 역할에게만 싣는다(ADR-063).
     * 그 밖의 요청자는 단계와 이름만 받는다. 빈 칸은 고를 수 없어 싣지 않는다. ChatGPT 가 요금제마다 칸 수를 달리
     * 보이는 것과 같다.
     */
    @Transactional(readOnly = true)
    public ModelTierOptions optionsFor(CurrentUser user, Agent agent) {
        ModelSetting setting = settings.read(user.groupId());
        List<ModelSetting.Stop> filled = setting.tiers().stream()
            .filter(stop -> !stop.isEmpty())
            .toList();
        boolean internal = InternalValuePolicy.visibleTo(user);
        String defaultProvider = internal && filled.stream().anyMatch(stop -> stop.provider() == null)
            ? modelOptions.optionsForAgent(user.groupId(), agent).defaultProvider()
            : null;
        return new ModelTierOptions(
            filled.stream()
                .map(stop -> internal ? view(stop, defaultProvider) : nameOnly(stop))
                .toList(),
            users.findById(user.id())
                .map(it -> tierOf(it.modelDefaultTier()))
                .orElse(null),
            setting.defaultTier(),
            user.isAdmin());
    }


    /**
     * 관리자가 고칠 그룹의 단계 정의와 그룹 기본 단계다.
     *
     * <p>에이전트를 받지 않는다. 단계 정의는 그룹의 설정이고 특정 에이전트에 매이지 않는다. 에이전트로 읽으면
     * 관리자가 대화를 시작할 수 없는 에이전트(다른 사용자의 비공개 에이전트)를 고른 화면이 설정을 읽지 못한다.
     * provider 를 비운 단계는 비운 채 낸다. 에이전트의 기본 provider 로 채워 보이면 저장할 때 그 값으로 굳는다.
     */
    @Transactional(readOnly = true)
    public GroupModelTiers groupTiers(CurrentUser admin) {
        if (!admin.isAdmin()) {
            throw new ApiException(ErrorCode.FORBIDDEN, "this action is limited to the group admin");
        }
        ModelSetting setting = settings.read(admin.groupId());
        return new GroupModelTiers(
            setting.tiers().stream()
                .map(ModelTierService::stored)
                .toList(),
            setting.defaultTier());
    }


    @Transactional
    public void saveUserDefault(CurrentUser user, ModelTier tier) {
        if (users.updateModelDefaultTier(user.id(), tier == null ? null : tier.name(), clock.instant().truncatedTo(ChronoUnit.MICROS)) == 0
            && !users.existsById(user.id())) {
            throw new ApiException(ErrorCode.FORBIDDEN, "the signed-in user does not exist");
        }
    }


    /**
     * 그룹의 칸 다섯과 그룹 기본 칸을 저장한다.
     *
     * <p>저장된 값과 provider 나 모델이 다른 칸만 그룹 목록({@link GroupModelCatalogService})과 견준다. 들고 있던
     * 목록에 없으면 다시 읽어 보고, 그래도 없으면 {@code VALIDATION_FAILED} 다. 바뀌지 않은 칸은 견주지 않아, 목록을
     * 읽지 못해도 effort 만 고치거나 칸을 비우는 저장은 된다. 바뀐 칸은 provider 를 비울 수 없다. 옮겨 온 정의의 빈
     * provider 는 바꾸지 않으면 그대로 둔다.
     *
     * <p>검증은 트랜잭션 밖에서 끝낸다. 목록 조회가 Hermes 를 부를 수 있어 DB 연결을 쥔 채 기다리지 않게 한다
     * ({@code AgentModelDefaultService.save} 와 같은 까닭).
     *
     * @throws ApiException {@code VALIDATION_FAILED}. 칸이 다섯 개가 아니거나 값이 맞지 않거나 목록에 없을 때.
     * {@code MODEL_HIDDEN}. 바뀐 칸이 그룹이 숨긴 모델일 때. 바뀐 칸이 있는데 목록을 읽지 못하면 그 오류가 그대로 나간다
     */
    public void saveGroup(CurrentUser user, List<ModelTierOptions.Tier> tiers, ModelTier defaultTier) {
        if (!user.isAdmin()) {
            throw new ApiException(ErrorCode.FORBIDDEN, "this action is limited to the group admin");
        }
        if (tiers == null || tiers.stream().anyMatch(tier -> tier == null || tier.tier() == null)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "all model tiers must be supplied exactly once");
        }
        List<ModelTierOptions.Tier> normalizedTiers = tiers.stream().map(ModelTierService::normalize).toList();
        if (normalizedTiers.size() != ModelTier.values().length
            || normalizedTiers.stream()
                .map(ModelTierOptions.Tier::tier)
                .distinct()
                .count() != ModelTier.values().length) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "all model tiers must be supplied exactly once");
        }
        for (ModelTierOptions.Tier tier : normalizedTiers) {
            if (!isValidMapping(tier)) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "invalid model tier definition");
            }
        }
        ModelSetting stored = settings.read(user.groupId());
        List<ModelTierOptions.Tier> changed = normalizedTiers.stream()
            .filter(tier -> !isFallback(tier))
            .filter(tier -> {
                ModelSetting.Stop before = stored.stop(tier.tier());
                return !Objects.equals(tier.provider(), before.provider()) || !Objects.equals(tier.model(), before.model());
            })
            .toList();
        for (ModelTierOptions.Tier tier : changed) {
            if (tier.provider() == null) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "a changed model tier must name its provider");
            }
            visibility.requireVisible(user.groupId(), ModelChoice.stored(tier.provider(), tier.model(), null));
        }
        requireOffered(user.groupId(), changed);
        settings.saveTiers(
            user.groupId(),
            normalizedTiers.stream()
                .sorted(Comparator.comparing(ModelTierOptions.Tier::tier))
                .map(tier -> new ModelSetting.Stop(tier.tier(), tier.provider(), tier.model(), tier.reasoningEffort()))
                .toList(),
            defaultTier);
    }


    /** 바뀐 칸이 모두 그룹 목록에 있는지 본다. 들고 있던 목록에 없으면 한 번 다시 읽는다. */
    private void requireOffered(Long groupId, List<ModelTierOptions.Tier> changed) {
        if (changed.isEmpty()) {
            return;
        }
        List<ModelTierOptions.Tier> missing = missingFrom(groupCatalog.catalogFor(groupId), changed);
        if (missing.isEmpty()) {
            return;
        }
        log.warn("바뀐 칸의 모델이 그룹 목록에 없어 다시 읽는다 tiers={}", tierNames(missing));
        missing = missingFrom(groupCatalog.refreshedCatalogFor(groupId), missing);
        if (!missing.isEmpty()) {
            log.warn("다시 읽어도 바뀐 칸의 모델이 그룹 목록에 없어 저장을 거절한다 tiers={}", tierNames(missing));
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "the selected model is not in the group model catalog");
        }
    }


    private static List<ModelTierOptions.Tier> missingFrom(GroupModelCatalog catalog, List<ModelTierOptions.Tier> tiers) {
        List<ModelTierOptions.Tier> missing = new ArrayList<>();
        for (ModelTierOptions.Tier tier : tiers) {
            if (!catalog.offers(tier.provider(), tier.model())) {
                missing.add(tier);
            }
        }
        return missing;
    }


    private static List<ModelTier> tierNames(List<ModelTierOptions.Tier> tiers) {
        return tiers.stream().map(ModelTierOptions.Tier::tier).toList();
    }


    /** 대화에 직접 고른 모델을 저장하기 전에 그룹이 숨긴 모델인지 본다. */
    @Transactional(readOnly = true)
    public void requireVisible(CurrentUser user, ModelChoice choice) {
        visibility.requireVisible(user.groupId(), choice);
    }


    /**
     * 대화에 저장하려는 effort 가 그 모델에서 고를 수 있는 값인지 본다.
     *
     * <p>{@code none} 만 판정한다. 그 에이전트의 목록(숨김 적용)에서 모델의 끄기 지원이 확인되지 않으면 거절한다.
     * 모델을 비웠으면 목록의 기본 모델로 본다. 목록 조회 실패({@code HERMES_UNAVAILABLE})는 그대로 올린다.
     *
     * @throws ApiException {@code VALIDATION_FAILED}. 그 모델에서 reasoning 을 끌 수 있는지 확인되지 않을 때
     */
    @Transactional(readOnly = true)
    public void requireEffortAllowed(CurrentUser user, Agent agent, ModelChoice choice) {
        if (!ModelChoice.EFFORT_NONE.equals(choice.reasoningEffort())) {
            return;
        }
        if (!modelOptions
            .optionsForAgent(user.groupId(), agent)
            .allowsEffort(choice.provider(), choice.model(), ModelChoice.EFFORT_NONE)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "reasoning cannot be turned off for this model");
        }
    }


    /**
     * 이번 실행이 Hermes 에 보낼 값을 정한다.
     *
     * <p>정한 모델이 그룹이 숨긴 것이면 다른 모델로 바꾸지 않고 {@code MODEL_HIDDEN} 으로 거절한다.
     */
    @Transactional(readOnly = true)
    public ResolvedModelTier resolve(CurrentUser user, Conversation conversation, Agent agent) {
        ResolvedModelTier resolved = resolveUnchecked(user, conversation, agent);
        requireRunnable(user.groupId(), agent, resolved.choice());
        return resolved;
    }


    /** 대화 없이 도는 실행이 보낼 에이전트 기본값이다. 숨김은 판정하지 않는다. */
    public ModelChoice detachedChoice(Agent agent) {
        return agentDefault(agent);
    }


    /**
     * 보내려는 값이 그룹이 숨긴 모델이면 {@code MODEL_HIDDEN} 으로 거절한다.
     *
     * <p>실행 줄을 먼저 만든 뒤 판정하는 실행이 쓴다. 판정하는 값과 실행 줄에 적고 보내는 값이 같은 것이어야
     * 하므로 값을 인자로 받는다.
     */
    @Transactional(readOnly = true)
    public void requireRunnable(CurrentUser user, Agent agent, ModelChoice choice) {
        requireRunnable(user.groupId(), agent, choice);
    }


    /** 이 실행이 돌 모델이 그룹이 숨긴 것이면 거절한다. 모델을 싣지 않는 실행은 profile 의 기본 모델로 본다(ADR-054). */
    private void requireRunnable(Long groupId, Agent agent, ModelChoice choice) {
        HiddenModels hidden = visibility.hiddenFor(groupId);
        if (hidden.isEmpty()) {
            return;
        }
        if (!choice.usesDefaultModel()) {
            if (hidden.hides(choice.provider(), choice.model())) {
                throw new ApiException(ErrorCode.MODEL_HIDDEN, "the selected model is hidden for this group");
            }
            return;
        }
        // 숨김이 있는 그룹만 여기에 온다. Hermes 를 실행마다 부르지 않도록 들고 있는 목록에서 읽는다.
        ModelChoice profile = modelOptions.profileDefaultOf(agent);
        if (hidden.hidesProfileDefault(profile.provider(), profile.model())) {
            throw new ApiException(ErrorCode.MODEL_HIDDEN, "the profile default model is hidden for this group");
        }
    }


    private ResolvedModelTier resolveUnchecked(CurrentUser user, Conversation conversation, Agent agent) {
        if (conversation.modelSelectionMode() == ModelSelectionMode.CUSTOM) {
            return new ResolvedModelTier(customChoice(conversation.modelChoice(), agent), null);
        }
        if (conversation.modelSelectionMode() == ModelSelectionMode.DEFAULT) {
            return new ResolvedModelTier(agentDefault(agent), null);
        }
        ModelTier selected = conversation.modelSelectionMode() == ModelSelectionMode.TIER
            ? conversation.modelTier()
            : users.findById(user.id())
                .map(it -> tierOf(it.modelDefaultTier()))
                .orElse(null);
        if (selected == null) {
            selected = settings.read(user.groupId()).defaultTier();
        }
        if (selected == null) {
            return new ResolvedModelTier(agentDefault(agent), null);
        }
        return resolveTier(user, selected, agent, false);
    }


    /**
     * 단계를 고르는 입력을 검증하며 실제 요청 값을 만든다. mapping 이 빈 단계는 에이전트 기본값으로 돈다.
     *
     * <p>단계가 가리키는 모델이 목록에 없으면 목록을 다시 읽고, 그래도 없으면 {@code VALIDATION_FAILED} 이다.
     */
    @Transactional(readOnly = true)
    public ResolvedModelTier resolveTier(CurrentUser user, ModelTier tier, Agent agent) {
        return resolveTier(user, tier, agent, true);
    }


    /**
     * @param strict 참이면 다시 읽은 뒤에도 목록에 없는 모델을 거절한다. 거짓이면(실행) 경고만 남기고 보낸다
     */
    private ResolvedModelTier resolveTier(CurrentUser user, ModelTier tier, Agent agent, boolean strict) {
        if (tier == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "unknown model tier");
        }
        ModelSetting.Stop definition = settings.read(user.groupId()).stop(tier);
        if (definition.isEmpty()) {
            ModelChoice fallback = agentDefault(agent);
            requireRunnable(user.groupId(), agent, fallback);
            return new ResolvedModelTier(fallback, tier);
        }
        ModelOptions options = modelOptions.optionsForAgent(user.groupId(), agent);
        String provider = definition.provider();
        if (provider == null) {
            provider = options.defaultProvider();
        }
        ModelChoice choice = ModelChoice.of(provider, definition.model(), definition.reasoningEffort());
        visibility.requireVisible(user.groupId(), choice);
        if (!options.offers(choice.provider(), choice.model())) {
            log.warn(
                "단계 모델이 들고 있던 목록에 없어 다시 읽는다 profile={} tier={} provider={} model={} providerModels={}",
                agent.hermesProfile(),
                tier,
                choice.provider(),
                choice.model(),
                providerModelCount(options, choice.provider()));
            options = modelOptions.refreshedOptionsForAgent(user.groupId(), agent);
            if (!options.offers(choice.provider(), choice.model())) {
                log.warn(
                    "다시 읽어도 단계 모델이 목록에 없다 {} profile={} tier={} provider={} model={} providerModels={}",
                    strict ? "입력을 거절한다" : "그대로 보낸다",
                    agent.hermesProfile(),
                    tier,
                    choice.provider(),
                    choice.model(),
                    providerModelCount(options, choice.provider()));
                if (strict) {
                    throw new ApiException(
                        ErrorCode.VALIDATION_FAILED, "the selected model is unavailable for this agent");
                }
            }
        }
        return new ResolvedModelTier(choice, tier);
    }


    /** 목록에서 그 provider 가 가진 모델 수다. 없는 provider 는 0 이다. */
    private static int providerModelCount(ModelOptions options, String provider) {
        return options.providers().stream()
            .filter(row -> row.slug().equals(provider))
            .mapToInt(row -> row.models().size())
            .sum();
    }


    /**
     * 에이전트에 저장된 기본 모델과 effort 다. 비어 있으면 요청에 싣지 않아 profile 의 값으로 돈다.
     *
     * <p>실행할 때는 Hermes 목록과 견주지 않는다. 목록 조회가 실패해도 기본값 대화가 돌게 하려는 것이다.
     * 목록에서 빠진 모델이면 Hermes 가 그 실행을 거절한다.
     */
    private static ModelChoice agentDefault(Agent agent) {
        return ModelChoice.stored(agent.defaultModelProvider(), agent.defaultModel(), agent.defaultReasoningEffort());
    }


    /** 대화가 모델을 비워 둔 직접 선택은 에이전트 기본 모델로 돌고, effort 는 대화가 고른 값이 먼저다. */
    private static ModelChoice customChoice(ModelChoice own, Agent agent) {
        if (!own.usesDefaultModel()) {
            return own;
        }
        ModelChoice base = agentDefault(agent);
        return ModelChoice.stored(
            base.provider(),
            base.model(),
            own.reasoningEffort() != null ? own.reasoningEffort() : base.reasoningEffort());
    }


    private static ModelTierOptions.Tier view(ModelSetting.Stop definition, String defaultProvider) {
        if (definition.isEmpty()) {
            return nameOnly(definition);
        }
        return new ModelTierOptions.Tier(
            definition.tier(),
            labelOf(definition.tier()),
            definition.provider() == null ? defaultProvider : definition.provider(),
            definition.model(),
            definition.reasoningEffort());
    }


    /** 저장된 값 그대로 담는다. 빈 칸을 채우지 않는다. */
    private static ModelTierOptions.Tier stored(ModelSetting.Stop definition) {
        return new ModelTierOptions.Tier(
            definition.tier(),
            labelOf(definition.tier()),
            definition.provider(),
            definition.model(),
            definition.reasoningEffort());
    }


    /** 단계와 이름만 담는다. 가리키는 모델을 싣지 않는 요청자와 mapping 이 빈 단계가 함께 쓴다. */
    private static ModelTierOptions.Tier nameOnly(ModelSetting.Stop definition) {
        return new ModelTierOptions.Tier(definition.tier(), labelOf(definition.tier()), null, null, null);
    }


    private static ModelTierOptions.Tier normalize(ModelTierOptions.Tier tier) {
        return new ModelTierOptions.Tier(
            tier.tier(),
            tier.label(),
            blankToNull(tier.provider()),
            blankToNull(tier.model()),
            tier.reasoningEffort());
    }


    private static boolean isValidMapping(ModelTierOptions.Tier tier) {
        if (tier == null || tier.tier() == null) {
            return false;
        }
        if (isFallback(tier)) {
            return true;
        }
        return tier.model() != null
            && tier.model().length() <= ModelChoice.MODEL_MAX_LENGTH
            && (tier.provider() == null || tier.provider().length() <= ModelChoice.PROVIDER_MAX_LENGTH)
            && tier.reasoningEffort() != null
            && ModelChoice.REASONING_EFFORTS.contains(tier.reasoningEffort());
    }


    private static boolean isFallback(ModelTierOptions.Tier tier) {
        return tier.provider() == null && tier.model() == null && tier.reasoningEffort() == null;
    }


    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        return stripped.isEmpty() ? null : stripped;
    }


    private static String labelOf(ModelTier tier) {
        return switch (tier) {
            case INSTANT -> "Instant";
            case MEDIUM -> "Medium";
            case HIGH -> "High";
            case EXTRA_HIGH -> "Extra High";
            case PRO -> "Pro";
        };
    }


    private static ModelTier tierOf(String value) {
        if (value == null) {
            return null;
        }
        try {
            return ModelTier.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "unknown stored model tier");
        }
    }
}
