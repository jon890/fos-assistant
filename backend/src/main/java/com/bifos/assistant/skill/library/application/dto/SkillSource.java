package com.bifos.assistant.skill.library.application.dto;

/**
 * 스킬이 어디서 왔는가.
 *
 * <p>Hermes 의 {@code provenance} 는 올린 스킬과 모델이 만든 로컬 스킬을 모두 {@code agent} 로 주므로
 * 쓰지 않는다. 업로드와 플러그인 설치는 각각 별도의 소유 기록으로 구분한다.
 */
public enum SkillSource {
    /** 이 화면에서 올린 스킬. 지금 버전 디렉터리에 있다 */
    UPLOADED,
    /** 플러그인이 붙이기로 설치한 스킬 */
    CONNECTOR,
    /** Hermes 가 스스로 가진 스킬 */
    HERMES
}
