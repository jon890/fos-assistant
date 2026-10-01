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

/** 그룹 단계와 사용자 기본값을 해석한다. */
@Service
@RequiredArgsConstructor
public class ModelTierService {

    private final ModelTierDefinitionRepository definitions;
    private final ModelTierGroupSettingRepository groupSettings;
    private final AppUserRepository users;
    private final ModelOptionsService modelOptions;

    @Transactional(readOnly = true)
    public ModelTierOptions optionsFor(CurrentUser user, Agent agent) {
        ModelOptions catalog = modelOptions.optionsForAgent(agent);
        return new ModelTierOptions(
                definitionsFor(user.groupId()).stream()
                        .map(definition -> view(definition, catalog.defaultProvider()))
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
        if (tiers.size() != ModelTier.values().length
                || tiers.stream().map(ModelTierOptions.Tier::tier).distinct().count() != ModelTier.values().length) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "all model tiers must be supplied exactly once");
        }
        for (ModelTierOptions.Tier tier : tiers) {
            if (tier.tier() == null
                    || tier.model() == null
                    || tier.model().isBlank()
                    || tier.model().strip().length() > ModelChoice.MODEL_MAX_LENGTH
                    || tier.provider() != null && tier.provider().strip().length() > ModelChoice.PROVIDER_MAX_LENGTH
                    || !ModelChoice.REASONING_EFFORTS.contains(tier.reasoningEffort())) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "invalid model tier definition");
            }
        }
        if (defaultTier != null && tiers.stream().noneMatch(tier -> tier.tier() == defaultTier)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "group default tier must be defined");
        }
        definitions.deleteByGroupId(user.groupId());
        definitions.saveAll(tiers.stream()
                .map(tier -> ModelTierDefinition.of(
                        user.groupId(), tier.tier(), tier.provider(), tier.model(), tier.reasoningEffort()))
                .toList());
        groupSettings.save(ModelTierGroupSetting.of(user.groupId(), defaultTier));
    }

    @Transactional(readOnly = true)
    public ResolvedModelTier resolve(CurrentUser user, Conversation conversation, Agent agent) {
        if (conversation.modelSelectionMode() == ModelSelectionMode.CUSTOM) {
            return new ResolvedModelTier(conversation.modelChoice(), null);
        }
        if (conversation.modelSelectionMode() == ModelSelectionMode.DEFAULT) {
            return new ResolvedModelTier(ModelChoice.defaults(), null);
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
            return new ResolvedModelTier(ModelChoice.defaults(), null);
        }
        return resolveTier(user, selected, agent);
    }

    /** 저장된 단계 또는 새 그룹의 초기 단계에서 실제 요청 값을 만든다. */
    @Transactional(readOnly = true)
    public ResolvedModelTier resolveTier(CurrentUser user, ModelTier tier, Agent agent) {
        if (tier == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "unknown model tier");
        }
        ModelTierDefinition definition = definitionsFor(user.groupId()).stream()
                .filter(candidate -> candidate.tier() == tier)
                .findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED, "unknown model tier"));
        String provider = definition.provider();
        if (provider == null) {
            provider = modelOptions.optionsForAgent(agent).defaultProvider();
        }
        ModelChoice choice = ModelChoice.of(provider, definition.model(), definition.reasoningEffort());
        requireAvailable(modelOptions.optionsForAgent(agent), choice);
        return new ResolvedModelTier(choice, tier);
    }

    @Transactional(readOnly = true)
    public void requireDefined(CurrentUser user, ModelTier tier) {
        if (tier == null
                || definitionsFor(user.groupId()).stream().noneMatch(definition -> definition.tier() == tier)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "unknown model tier");
        }
    }

    /** 고급 직접 선택도 실행할 에이전트의 catalog 안에 있어야 한다. */
    @Transactional(readOnly = true)
    public void requireAvailable(Agent agent, ModelChoice choice) {
        if (choice.provider() != null) {
            requireAvailable(modelOptions.optionsForAgent(agent), choice);
        }
    }

    private static ModelTierOptions.Tier view(ModelTierDefinition definition, String defaultProvider) {
        return new ModelTierOptions.Tier(
                definition.tier(),
                labelOf(definition.tier()),
                definition.provider() == null ? defaultProvider : definition.provider(),
                definition.model(),
                definition.reasoningEffort());
    }

    /**
     * V41 뒤 새로 생긴 그룹은 초기 행을 아직 갖지 않는다. 조회에서 저장하지 않고 같은 초기 정의를 보인다.
     * 일부 행만 있으면 관리자가 저장한 정의가 깨진 것이므로 임의의 기본값과 섞지 않는다.
     */
    private List<ModelTierDefinition> definitionsFor(Long groupId) {
        List<ModelTierDefinition> stored = definitions.findByGroupIdOrderByTier(groupId);
        if (stored.isEmpty()) {
            return initialDefinitions(groupId);
        }
        Map<ModelTier, ModelTierDefinition> byTier = stored.stream()
                .collect(Collectors.toMap(ModelTierDefinition::tier, Function.identity(), (left, right) -> left));
        if (byTier.size() != ModelTier.values().length
                || Arrays.stream(ModelTier.values()).anyMatch(tier -> !byTier.containsKey(tier))) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "stored model tier definitions are incomplete");
        }
        return Arrays.stream(ModelTier.values()).map(byTier::get).toList();
    }

    private static List<ModelTierDefinition> initialDefinitions(Long groupId) {
        return List.of(
                ModelTierDefinition.of(groupId, ModelTier.FAST, null, "gpt-6-luna", "low"),
                ModelTierDefinition.of(groupId, ModelTier.BALANCED, null, "gpt-6-luna", "medium"),
                ModelTierDefinition.of(groupId, ModelTier.DEEP, null, "gpt-6.1-sol", "high"));
    }

    private static String labelOf(ModelTier tier) {
        return switch (tier) {
            case FAST -> "빠르게";
            case BALANCED -> "균형";
            case DEEP -> "깊게";
        };
    }

    private static void requireAvailable(ModelOptions options, ModelChoice choice) {
        boolean available = options.providers().stream()
                .anyMatch(provider -> provider.slug().equals(choice.provider())
                        && provider.models().contains(choice.model()));
        if (!available) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "the selected model is unavailable for this agent");
        }
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
