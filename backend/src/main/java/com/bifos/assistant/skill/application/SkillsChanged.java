package com.bifos.assistant.skill.application;

/**
 * 그 에이전트의 스킬 저장, 지우기, 켜고 끄기가 Hermes 에 반영됐다는 사건이다.
 *
 * <p>{@link SkillCommandCatalog} 가 받아 그 에이전트의 켜진 스킬 캐시를 비운다.
 *
 * @param agentId 스킬이 바뀐 에이전트
 */
public record SkillsChanged(Long agentId) {
}
