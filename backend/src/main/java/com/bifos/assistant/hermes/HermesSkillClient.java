package com.bifos.assistant.hermes;

import java.util.List;

/**
 * Hermes 대시보드의 스킬 경로를 부른다.
 *
 * <p>인터페이스로 둔 것은 실제 Hermes 없이 Control Plane 을 검사하기 위해서다. 무엇을 부르는지는
 * {@code docs/hermes/profiles.md} 의 「Control Plane 이 부르는 대시보드 plugin 경로」가 갖는다. 게시할
 * 경로와 도구 목록을 정하는 일은 {@code SkillPublisher} 가 안다.
 */
public interface HermesSkillClient {

    /**
     * 대시보드가 아는 스킬 한 줄이다.
     *
     * @param enabled 전역 {@code skills.disabled} 만 반영한 값
     */
    record HermesSkill(String name, String description, boolean enabled) {}

    /** 그 profile 의 스킬 목록이다. 올린 스킬과 Hermes 가 스스로 가진 스킬이 함께 온다. */
    List<HermesSkill> list(String profile);

    /** 스킬 하나를 전역으로 켜거나 끈다. */
    void toggle(String profile, String name, boolean enabled);

    /**
     * {@code skills.external_dirs} 를 게시한다.
     *
     * @param externalDirs Hermes 쪽 경로 목록. 비어 있으면 게시 해제다
     * @param apiServerToolsets {@code null} 이 아니면 {@code platform_toolsets.api_server} 를 같은 본문에
     *     함께 쓴다. {@code null} 이면 도구를 건드리지 않는다
     * @throws HermesRequestRejected 대시보드가 4xx 로 분명히 거절했다. 설정은 바뀌지 않았다
     */
    void publish(String profile, List<String> externalDirs, List<String> apiServerToolsets);
}
