package com.bifos.assistant.chat;

import com.bifos.assistant.chat.modelchoice.application.ModelSettingStore;
import com.bifos.assistant.chat.modelchoice.application.dto.HiddenModels;
import com.bifos.assistant.chat.modelchoice.application.dto.ModelSetting;
import com.bifos.assistant.model.domain.type.ModelTier;
import java.util.ArrayList;
import java.util.List;

/** DB 없이 그룹 모델 설정 하나를 들고 있는 가짜 저장소다. 그룹을 가리지 않는다. 읽은 수와 저장한 수를 센다. */
public class FakeModelSettingStore extends ModelSettingStore {

    private ModelSetting setting = ModelSetting.empty();

    private int reads;

    private int saves;


    public FakeModelSettingStore() {
        super(null, null);
    }


    /** 칸 다섯을 순서대로 받는다. 빠진 칸은 빈 칸이다. */
    public FakeModelSettingStore tiers(ModelSetting.Stop... stops) {
        List<ModelSetting.Stop> tiers = new ArrayList<>(ModelSetting.empty().tiers());
        for (ModelSetting.Stop stop : stops) {
            tiers.set(stop.tier().ordinal(), stop);
        }
        setting = setting.withTiers(tiers, setting.defaultTier());
        return this;
    }


    public FakeModelSettingStore defaultTier(ModelTier tier) {
        setting = setting.withTiers(setting.tiers(), tier);
        return this;
    }


    public FakeModelSettingStore hidden(HiddenModels hidden) {
        setting = setting.withHidden(hidden);
        return this;
    }


    public ModelSetting current() {
        return setting;
    }


    public int reads() {
        return reads;
    }


    public int saves() {
        return saves;
    }


    @Override
    public ModelSetting read(Long groupId) {
        reads++;
        return setting;
    }


    @Override
    public void saveTiers(Long groupId, List<ModelSetting.Stop> tiers, ModelTier defaultTier) {
        saves++;
        setting = setting.withTiers(tiers, defaultTier);
    }


    @Override
    public void saveHidden(Long groupId, HiddenModels hidden) {
        saves++;
        setting = setting.withHidden(hidden);
    }
}
