package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.chat.modelchoice.application.ModelSettingStore;
import com.bifos.assistant.chat.modelchoice.application.ModelTierService;
import com.bifos.assistant.chat.modelchoice.application.dto.HiddenModels;
import com.bifos.assistant.chat.modelchoice.application.dto.ModelSetting;
import com.bifos.assistant.chat.modelchoice.application.dto.ModelTierOptions;
import com.bifos.assistant.chat.modelchoice.application.dto.ResolvedModelTier;
import com.bifos.assistant.chat.modelchoice.domain.GroupSetting;
import com.bifos.assistant.chat.modelchoice.domain.GroupSettingId;
import com.bifos.assistant.chat.modelchoice.infra.GroupSettingRepository;
import com.bifos.assistant.model.domain.ModelChoice;
import com.bifos.assistant.model.domain.type.ModelTier;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/** 그룹 모델 설정이 {@code group_setting} 의 {@code MODEL} 줄 하나로 저장되고 다시 읽히는지 실제 저장소에서 본다. */
@BackendIntegrationTest
class ModelTierPersistenceTest {

    private static final Agent AGENT = Agent.of(
        "tier-test",
        "tier-test",
        "tier-test",
        "http://hermes.invalid",
        CostMode.SUBSCRIPTION,
        CredentialScope.SHARED_HOUSEHOLD,
        AgentVisibility.GROUP,
        null);

    private static final Long GROUP_ID = 9_000_000_001L;

    private static final GroupSettingId MODEL_ID = new GroupSettingId(GROUP_ID, GroupSetting.MODEL);

    @Autowired
    ModelTierService tiers;

    @Autowired
    ModelSettingStore store;

    @Autowired
    GroupSettingRepository settings;

    @Autowired
    TransactionTemplate transactions;


    @AfterEach
    void cleanUp() {
        transactions.executeWithoutResult(status -> settings.deleteById(MODEL_ID));
    }


    @Test
    @DisplayName("줄이 없는 그룹은 다섯 칸이 모두 빈 설정으로 읽히고 읽기만으로는 줄을 만들지 않는다")
    void missingRowReadsAsEmptySetting() {
        ModelSetting setting = store.read(GROUP_ID);

        assertThat(setting.tiers()).extracting(ModelSetting.Stop::tier).containsExactly(ModelTier.values());
        assertThat(setting.tiers()).allMatch(ModelSetting.Stop::isEmpty);
        assertThat(setting.defaultTier()).isNull();
        assertThat(setting.hidden().isEmpty()).isTrue();
        assertThat(settings.findById(MODEL_ID)).isEmpty();
    }


    @Test
    @DisplayName("칸과 숨김을 따로 저장해도 한 줄에 함께 남고 서로의 값을 덮지 않는다")
    void tiersAndHiddenShareOneRow() {
        store.saveHidden(GROUP_ID, new HiddenModels(List.of(new HiddenModels.Entry("provider-a", null))));
        store.saveTiers(GROUP_ID, stops("example-fast", "example-deep"), ModelTier.HIGH);
        store.saveTiers(GROUP_ID, stops("example-deep", "example-fast"), ModelTier.INSTANT);

        ModelSetting setting = store.read(GROUP_ID);
        assertThat(setting.tiers()).extracting(ModelSetting.Stop::model)
            .containsExactly("example-deep", "example-fast", "example-fast", null, null);
        assertThat(setting.defaultTier()).isEqualTo(ModelTier.INSTANT);
        assertThat(setting.hidden().entries()).containsExactly(new HiddenModels.Entry("provider-a", null));
        GroupSetting row = settings.findById(MODEL_ID).orElseThrow();
        assertThat(row.settingValue()).contains("\"version\":1");
        assertThat(row.updatedAt()).isNotNull();
    }


    @Test
    @DisplayName("모르는 버전의 설정 줄은 빈 설정으로 보지 않고 오류로 올린다")
    void unknownVersionIsAnError() {
        transactions.executeWithoutResult(status -> settings.save(GroupSetting.of(
            GROUP_ID, GroupSetting.MODEL, "{\"version\":99,\"tiers\":[],\"defaultTier\":null,\"hidden\":[]}",
            Instant.parse("2026-11-01T00:00:00Z"))));

        assertThatThrownBy(() -> store.read(GROUP_ID)).isInstanceOf(IllegalStateException.class);
    }


    @Test
    @DisplayName("관리자가 비어 있는 다섯 칸을 저장하면 목록을 읽지 않고 저장되며 profile 기본값으로 해석한다")
    void storedEmptyDefinitionsUseProfileDefaults() {
        CurrentUser admin = new CurrentUser(1L, "admin@example.com", "관리자", GROUP_ID, UserRole.ADMIN);
        List<ModelTierOptions.Tier> fallback = Arrays.stream(ModelTier.values())
            .map(tier -> new ModelTierOptions.Tier(tier, null, null, null, null))
            .toList();

        tiers.saveGroup(admin, fallback, ModelTier.MEDIUM);
        ResolvedModelTier resolved = tiers.resolveTier(admin, ModelTier.INSTANT, AGENT);
        ModelTierOptions options = tiers.optionsFor(admin, AGENT);

        assertThat(resolved.tier()).isEqualTo(ModelTier.INSTANT);
        assertThat(resolved.choice()).isEqualTo(ModelChoice.defaults());
        assertThat(options.tiers()).as("빈 칸은 고르는 목록에 싣지 않는다").isEmpty();
        assertThat(options.groupDefaultTier()).isEqualTo(ModelTier.MEDIUM);
        assertThat(settings.findById(MODEL_ID)).isPresent();
    }


    private static List<ModelSetting.Stop> stops(String instantModel, String mediumModel) {
        return List.of(
            new ModelSetting.Stop(ModelTier.INSTANT, "openai-codex", instantModel, "low"),
            new ModelSetting.Stop(ModelTier.MEDIUM, "openai-codex", mediumModel, "medium"),
            new ModelSetting.Stop(ModelTier.HIGH, "openai-codex", "example-fast", "high"),
            ModelSetting.Stop.empty(ModelTier.EXTRA_HIGH),
            ModelSetting.Stop.empty(ModelTier.PRO));
    }
}
