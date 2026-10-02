package com.bifos.assistant.chat.application;

import com.bifos.assistant.chat.domain.ModelChoice;

/**
 * 관리자가 한 에이전트의 기본 모델과 그룹의 숨김을 정할 때 보는 값이다.
 *
 * @param agentDefault 에이전트에 저장된 기본 provider, 모델, effort. 정하지 않았으면 세 값이 null 이다
 * @param catalog 숨김을 적용하지 않은 목록. 기본 provider 와 기본 모델은 profile 의 값이다. Hermes 가 목록을
 *     답하지 못했으면 null 이다. 그때도 저장된 기본값을 비우고 숨김을 푸는 일은 할 수 있어야 한다
 * @param hidden 그룹이 숨긴 provider 와 모델
 */
public record AgentModelSettings(ModelChoice agentDefault, ModelOptions catalog, HiddenModels hidden) {}
