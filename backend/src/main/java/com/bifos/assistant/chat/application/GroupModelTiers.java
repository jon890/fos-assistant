package com.bifos.assistant.chat.application;

import com.bifos.assistant.model.domain.type.ModelTier;
import java.util.List;

/**
 * 그룹에 저장된 단계 정의와 그룹 기본 단계다. 관리자가 고치려고 읽는다.
 *
 * <p>저장된 값 그대로다. provider 를 비운 단계는 비운 채 싣는다. 어느 에이전트의 기본 provider 로도 채우지 않는다.
 */
public record GroupModelTiers(List<ModelTierOptions.Tier> tiers, ModelTier groupDefaultTier) {}
