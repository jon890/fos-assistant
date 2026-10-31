package com.bifos.assistant.chat.modelchoice.application;

import com.bifos.assistant.chat.modelchoice.application.dto.HiddenModels;
import com.bifos.assistant.chat.modelchoice.application.dto.ModelSetting;
import com.bifos.assistant.chat.modelchoice.domain.GroupSetting;
import com.bifos.assistant.chat.modelchoice.domain.GroupSettingId;
import com.bifos.assistant.chat.modelchoice.infra.GroupSettingRepository;
import com.bifos.assistant.model.domain.type.ModelTier;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * 그룹의 모델 설정을 {@code group_setting} 의 {@code MODEL} 줄 하나로 읽고 쓴다(ADR-20261101 / model-setting).
 *
 * <p>JSON 은 버전 번호를 함께 둔다. 저장할 때 형식을 검사하고, 읽을 때 모르는 버전이거나 형식이 깨졌으면 설정이 없는
 * 것으로 보지 않고 오류로 올린다. 없는 것으로 보면 관리자가 정한 숨김이 풀린 채 실행된다.
 *
 * <p>칸과 숨김은 한 줄에 함께 있어 한쪽만 바꾸는 저장도 줄 전체를 다시 쓴다. 잠그고 읽어 동시에 온 다른 쪽 저장을
 * 덮지 않는다. 그룹에 줄이 아직 없으면 잠글 줄이 없다. 그때 첫 저장 둘이 동시에 오면 한쪽이 중복 key 나 deadlock
 * 오류로 실패한다. 값을 조용히 잃지는 않고, 관리자가 다시 저장하면 된다. 관리자 한 사람이 드물게 바꾸는 설정이라
 * 다시 시도하는 길을 두지 않는다.
 */
@Service
@RequiredArgsConstructor
public class ModelSettingStore {

    /** 지금 쓰는 JSON 의 버전이다. 모양을 바꾸면 올리고, 옛 버전을 읽는 길을 함께 둔다. */
    static final int VERSION = 1;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final GroupSettingRepository settings;
    private final Clock clock;


    /** 줄이 없는 그룹은 모든 칸이 빈 설정이다. */
    @Transactional(readOnly = true)
    public ModelSetting read(Long groupId) {
        return settings.findById(new GroupSettingId(groupId, GroupSetting.MODEL))
            .map(row -> decode(row.settingValue()))
            .orElseGet(ModelSetting::empty);
    }


    /** 칸 다섯과 그룹 기본 칸을 바꾼다. 숨김은 그대로 둔다. */
    @Transactional
    public void saveTiers(Long groupId, List<ModelSetting.Stop> tiers, ModelTier defaultTier) {
        update(groupId, readForUpdate(groupId).withTiers(tiers, defaultTier));
    }


    /** 숨김 목록을 바꾼다. 칸은 그대로 둔다. */
    @Transactional
    public void saveHidden(Long groupId, HiddenModels hidden) {
        update(groupId, readForUpdate(groupId).withHidden(hidden));
    }


    private ModelSetting readForUpdate(Long groupId) {
        return settings.findForUpdate(new GroupSettingId(groupId, GroupSetting.MODEL))
            .map(row -> decode(row.settingValue()))
            .orElseGet(ModelSetting::empty);
    }


    private void update(Long groupId, ModelSetting setting) {
        String value = encode(setting);
        var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        GroupSettingId id = new GroupSettingId(groupId, GroupSetting.MODEL);
        settings.findById(id).ifPresentOrElse(
            row -> row.change(value, now),
            () -> settings.save(GroupSetting.of(groupId, GroupSetting.MODEL, value, now)));
    }


    /** 저장할 설정의 모양을 검사해 JSON 으로 바꾼다. 칸의 값이 맞는지는 부르는 쪽이 이미 본다. */
    static String encode(ModelSetting setting) {
        List<ModelSetting.Stop> tiers = setting.tiers();
        ModelTier[] stops = ModelTier.values();
        if (tiers.size() != stops.length) {
            throw new IllegalArgumentException("model setting must have every tier once");
        }
        List<StoredTier> storedTiers = new ArrayList<>();
        for (int i = 0; i < stops.length; i++) {
            ModelSetting.Stop stop = tiers.get(i);
            if (stop == null || stop.tier() != stops[i]) {
                throw new IllegalArgumentException("model setting tiers must follow the tier order");
            }
            storedTiers.add(new StoredTier(stop.tier().name(), stop.provider(), stop.model(), stop.reasoningEffort()));
        }
        List<StoredHidden> storedHidden = setting.hidden().entries().stream()
            .map(entry -> new StoredHidden(entry.provider(), entry.model()))
            .toList();
        Stored stored = new Stored(
            VERSION,
            storedTiers,
            setting.defaultTier() == null ? null : setting.defaultTier().name(),
            storedHidden);
        return JSON.writeValueAsString(stored);
    }


    /**
     * 저장된 JSON 을 설정으로 읽는다. 마이그레이션 검사가 옮긴 JSON 을 같은 해석으로 읽으려고 연다.
     *
     * @throws IllegalStateException 모르는 버전이거나 형식이 깨졌을 때
     */
    public static ModelSetting decode(String value) {
        Stored stored;
        try {
            stored = JSON.readValue(value, Stored.class);
        } catch (JacksonException ex) {
            throw new IllegalStateException("stored model setting is not readable", ex);
        }
        if (stored.version() == null || stored.version() != VERSION) {
            throw new IllegalStateException("stored model setting has an unknown version");
        }
        if (stored.tiers() == null || stored.hidden() == null) {
            throw new IllegalStateException("stored model setting is incomplete");
        }
        ModelTier[] stops = ModelTier.values();
        if (stored.tiers().size() != stops.length) {
            throw new IllegalStateException("stored model setting must have every tier once");
        }
        List<ModelSetting.Stop> tiers = new ArrayList<>();
        for (int i = 0; i < stops.length; i++) {
            StoredTier tier = stored.tiers().get(i);
            if (tier == null || !stops[i].name().equals(tier.tier())) {
                throw new IllegalStateException("stored model setting tiers are out of order");
            }
            tiers.add(new ModelSetting.Stop(stops[i], tier.provider(), tier.model(), tier.reasoningEffort()));
        }
        ModelTier defaultTier = stored.defaultTier() == null ? null : tierOf(stored.defaultTier());
        List<HiddenModels.Entry> hidden = stored.hidden().stream()
            .map(entry -> {
                if (entry == null || entry.provider() == null) {
                    throw new IllegalStateException("stored model setting has an invalid hidden entry");
                }
                return new HiddenModels.Entry(entry.provider(), entry.model());
            })
            .toList();
        return new ModelSetting(tiers, defaultTier, new HiddenModels(hidden));
    }


    private static ModelTier tierOf(String value) {
        try {
            return ModelTier.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("stored model setting has an unknown tier", ex);
        }
    }


    /** 저장하는 JSON 의 모양이다. 칸 이름과 키 이름이 저장되므로 바꾸려면 버전을 올린다. */
    private record Stored(Integer version, List<StoredTier> tiers, String defaultTier, List<StoredHidden> hidden) {
    }

    private record StoredTier(String tier, String provider, String model, String reasoningEffort) {
    }

    /** @param model null 이면 그 provider 전체다 */
    private record StoredHidden(String provider, String model) {
    }
}
