package com.bifos.assistant.chat.application;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.domain.ModelChoice;
import com.bifos.assistant.chat.domain.ModelTierDefinition;
import com.bifos.assistant.chat.domain.ModelTierGroupSetting;
import com.bifos.assistant.chat.domain.type.ModelSelectionMode;
import com.bifos.assistant.chat.domain.type.ModelTier;
import com.bifos.assistant.chat.infra.ModelTierDefinitionRepository;
import com.bifos.assistant.chat.infra.ModelTierGroupSettingRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.user.infra.AppUserRepository;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 그룹 단계와 사용자 기본값, 에이전트 기본 모델을 해석한다.
 *
 * <p>단계 정의는 DB 의 행만 읽는다. 행이 없는 그룹의 세 단계는 모두 빈 mapping 이고 에이전트 기본값으로 돈다.
 * 에이전트 기본값도 없으면 요청에 모델을 싣지 않아 Hermes profile 의 값으로 돈다(ADR-054).
 *
 * <p>실행 직전의 숨김 판정은 profile 의 기본 모델까지 본다. 요청에 모델을 싣지 않는 실행도 그 모델이 그룹이
 * 숨긴 것이면 거절한다.
 */
@Service
@RequiredArgsConstructor
public class ModelTierService {

    private final ModelTierDefinitionRepository definitions;
    private final ModelTierGroupSettingRepository groupSettings;
    private final AppUserRepository users;
    private final ModelOptionsService modelOptions;
    private final ModelVisibilityService visibility;

    @Transactional(readOnly = true)
    public ModelTierOptions optionsFor(CurrentUser user, Agent agent) {
        List<ModelTierDefinition> definitions = definitionsFor(user.groupId());
        String defaultProvider = needsDefaultProvider(definitions)
                ? modelOptions.optionsForAgent(user.groupId(), agent).defaultProvider()
                : null;
        return new ModelTierOptions(
                definitions.stream()
                        .map(definition -> view(definition, defaultProvider))
                        .toList(),
                users.findById(user.id())
                        .map(it -> tierOf(it.modelDefaultTier()))
                        .orElse(null),
                groupSettings
                        .findById(user.groupId())
                        .map(it -> it.defaultTier())
                        .orElse(null),
                user.isAdmin());
    }

    @Transactional
    public void saveUserDefault(CurrentUser user, ModelTier tier) {
        if (users.updateModelDefaultTier(user.id(), tier == null ? null : tier.name()) == 0) {
            throw new ApiException(ErrorCode.FORBIDDEN, "the signed-in user does not exist");
        }
    }

    @Transactional
    public void saveGroup(CurrentUser user, List<ModelTierOptions.Tier> tiers, ModelTier defaultTier) {
        if (!user.isAdmin()) {
            throw new ApiException(ErrorCode.FORBIDDEN, "this action is limited to the group admin");
        }
        List<ModelTierOptions.Tier> normalizedTiers =
                tiers.stream().map(ModelTierService::normalize).toList();
        if (normalizedTiers.size() != ModelTier.values().length
                || normalizedTiers.stream()
                                .map(ModelTierOptions.Tier::tier)
                                .distinct()
                                .count()
                        != ModelTier.values().length) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "all model tiers must be supplied exactly once");
        }
        for (ModelTierOptions.Tier tier : normalizedTiers) {
            if (!isValidMapping(tier)) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "invalid model tier definition");
            }
        }
        if (defaultTier != null && normalizedTiers.stream().noneMatch(tier -> tier.tier() == defaultTier)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "group default tier must be defined");
        }
        // provider 를 비운 정의는 에이전트마다 provider 가 달라 여기서 판정하지 못한다. 실행할 때 그 에이전트의 목록으로 본다.
        for (ModelTierOptions.Tier tier : normalizedTiers) {
            if (tier.provider() != null) {
                visibility.requireVisible(user.groupId(), ModelChoice.stored(tier.provider(), tier.model(), null));
            }
        }
        definitions.deleteByGroupId(user.groupId());
        definitions.flush();
        definitions.saveAll(normalizedTiers.stream()
                .map(tier -> ModelTierDefinition.of(
                        user.groupId(), tier.tier(), tier.provider(), tier.model(), tier.reasoningEffort()))
                .toList());
        groupSettings.save(ModelTierGroupSetting.of(user.groupId(), defaultTier));
    }

    /** 대화에 직접 고른 모델을 저장하기 전에 그룹이 숨긴 모델인지 본다. */
    @Transactional(readOnly = true)
    public void requireVisible(CurrentUser user, ModelChoice choice) {
        visibility.requireVisible(user.groupId(), choice);
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

    /** 대화 없이 도는 실행이 보낼 에이전트 기본값이다. 숨김 판정을 지난다. */
    @Transactional(readOnly = true)
    public ModelChoice resolveDetached(CurrentUser user, Agent agent) {
        ModelChoice choice = agentDefault(agent);
        requireRunnable(user.groupId(), agent, choice);
        return choice;
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
            selected = groupSettings
                    .findById(user.groupId())
                    .map(it -> it.defaultTier())
                    .orElse(null);
        }
        if (selected == null) {
            return new ResolvedModelTier(agentDefault(agent), null);
        }
        return resolveTier(user, selected, agent);
    }

    /** 저장된 단계에서 실제 요청 값을 만든다. mapping 이 빈 단계는 에이전트 기본값으로 돈다. */
    @Transactional(readOnly = true)
    public ResolvedModelTier resolveTier(CurrentUser user, ModelTier tier, Agent agent) {
        if (tier == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "unknown model tier");
        }
        ModelTierDefinition definition = definitionsFor(user.groupId()).stream()
                .filter(candidate -> candidate.tier() == tier)
                .findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED, "unknown model tier"));
        if (isFallback(definition)) {
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
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "the selected model is unavailable for this agent");
        }
        return new ResolvedModelTier(choice, tier);
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

    private static ModelTierOptions.Tier view(ModelTierDefinition definition, String defaultProvider) {
        if (isFallback(definition)) {
            return new ModelTierOptions.Tier(definition.tier(), labelOf(definition.tier()), null, null, null);
        }
        return new ModelTierOptions.Tier(
                definition.tier(),
                labelOf(definition.tier()),
                definition.provider() == null ? defaultProvider : definition.provider(),
                definition.model(),
                definition.reasoningEffort());
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

    private static boolean isFallback(ModelTierDefinition definition) {
        return definition.provider() == null && definition.model() == null && definition.reasoningEffort() == null;
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        return stripped.isEmpty() ? null : stripped;
    }

    /**
     * 정의 행이 없는 그룹은 세 단계를 빈 mapping 으로 보인다. 조회에서 저장하지 않는다.
     * 일부 행만 있으면 관리자가 저장한 정의가 깨진 것이므로 임의의 기본값과 섞지 않는다.
     */
    private List<ModelTierDefinition> definitionsFor(Long groupId) {
        List<ModelTierDefinition> stored = definitions.findByGroupIdOrderByTier(groupId);
        if (stored.isEmpty()) {
            return Arrays.stream(ModelTier.values())
                    .map(tier -> ModelTierDefinition.of(groupId, tier, null, null, null))
                    .toList();
        }
        Map<ModelTier, ModelTierDefinition> byTier = stored.stream()
                .collect(Collectors.toMap(ModelTierDefinition::tier, Function.identity(), (left, right) -> left));
        if (byTier.size() != ModelTier.values().length
                || Arrays.stream(ModelTier.values()).anyMatch(tier -> !byTier.containsKey(tier))) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "stored model tier definitions are incomplete");
        }
        return Arrays.stream(ModelTier.values()).map(byTier::get).toList();
    }

    private static boolean needsDefaultProvider(List<ModelTierDefinition> definitions) {
        return definitions.stream().anyMatch(definition -> !isFallback(definition) && definition.provider() == null);
    }

    private static String labelOf(ModelTier tier) {
        return switch (tier) {
            case FAST -> "빠르게";
            case BALANCED -> "균형";
            case DEEP -> "깊게";
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
