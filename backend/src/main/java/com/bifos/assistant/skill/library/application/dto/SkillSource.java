package com.bifos.assistant.skill.application;

/**
 * 스킬이 어디서 왔는가.
 *
 * <p>Hermes 의 {@code provenance} 는 올린 스킬과 모델이 만든 로컬 스킬을 모두 {@code agent} 로 주므로
 * 쓰지 않는다. 지금 버전 디렉터리에 있는 이름이 {@link #UPLOADED}, 나머지가 {@link #HERMES} 다.
 */
public enum SkillSource {
    /** 이 화면에서 올린 스킬. 지금 버전 디렉터리에 있다 */
    UPLOADED,
    /** Hermes 가 스스로 가진 스킬 */
    HERMES
}
