package com.bifos.assistant.admin.model.presentation;

import com.bifos.assistant.chat.modelchoice.application.AgentModelDefaultService;
import com.bifos.assistant.chat.modelchoice.application.GroupModelCatalogService;
import com.bifos.assistant.chat.modelchoice.application.dto.GroupModelTiers;
import com.bifos.assistant.chat.modelchoice.application.ModelTierService;
import com.bifos.assistant.chat.modelchoice.application.ModelVisibilityService;
import com.bifos.assistant.admin.model.presentation.ModelAdminDtos.AgentModelDefaultView;
import com.bifos.assistant.admin.model.presentation.ModelAdminDtos.AgentModelSettingsView;
import com.bifos.assistant.admin.model.presentation.ModelAdminDtos.ChooseModelRequest;
import com.bifos.assistant.admin.model.presentation.ModelAdminDtos.GroupModelCatalogView;
import com.bifos.assistant.admin.model.presentation.ModelAdminDtos.HiddenModelsView;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.auth.CurrentUserProvider;
import com.bifos.assistant.shared.error.ApiException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 관리자가 에이전트 기본 모델과 그룹의 모델 숨김을 정하고(ADR-054) 그룹의 단계 정의를 읽는다(ADR-063). */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class ModelAdminController {

    private final CurrentUserProvider currentUser;
    private final AgentModelDefaultService modelDefaults;
    private final ModelVisibilityService visibility;
    private final ModelTierService modelTiers;
    private final GroupModelCatalogService groupCatalog;


    /** 그룹의 단계 정의와 그룹 기본 단계다. 에이전트 없이 읽는다. 저장은 {@code PUT /api/v1/chat/model-tiers/group} 이 한다. */
    @GetMapping("/model-tiers")
    public GroupModelTiers groupModelTiers() {
        return modelTiers.groupTiers(currentUser.requireAdmin());
    }


    /**
     * 그룹 하나의 모델 목록이다. 단계별 모델을 고르는 칸이 쓴다.
     *
     * <p>Hermes 가 목록을 답하지 못해도 200 으로 {@code catalog} 를 null 로 둔다. 화면은 그때 저장된 값만 보이고
     * 새 모델을 고르지 못하게 한다.
     */
    @GetMapping("/model-catalog")
    public GroupModelCatalogView groupModelCatalog() {
        CurrentUser user = currentUser.requireAdmin();
        try {
            return GroupModelCatalogView.from(groupCatalog.catalogFor(user.groupId()));
        } catch (ApiException ex) {
            log.warn("그룹 모델 목록을 읽지 못해 목록 없이 돌려준다 code={}", ex.code());
            return GroupModelCatalogView.missing();
        }
    }


    @GetMapping("/agents/{code}/model-settings")
    public AgentModelSettingsView modelSettings(@PathVariable String code) {
        CurrentUser user = currentUser.requireAdmin();
        return AgentModelSettingsView.from(modelDefaults.settingsFor(user, code));
    }


    /** 세 값을 모두 비워 보내면 profile 의 값으로 돌아간다. */
    @PutMapping("/agents/{code}/model-default")
    public AgentModelDefaultView updateModelDefault(
        @PathVariable String code, @RequestBody ChooseModelRequest request) {
        CurrentUser user = currentUser.requireAdmin();
        return AgentModelDefaultView.from(modelDefaults.save(user, code, request.toChoice()));
    }


    @GetMapping("/model-hidden")
    public HiddenModelsView hiddenModels() {
        CurrentUser user = currentUser.requireAdmin();
        return HiddenModelsView.from(visibility.hiddenFor(user.groupId()));
    }


    @PutMapping("/model-hidden")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateHiddenModels(@Valid @RequestBody HiddenModelsView request) {
        visibility.save(currentUser.requireAdmin(), request.toEntries());
    }
}
