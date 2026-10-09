package com.bifos.assistant.hermes;

import java.util.List;

/**
 * Hermes 대시보드의 스킬 경로를 부른다.
 *
 * <p>인터페이스로 둔 것은 실제 Hermes 없이 Control Plane 을 검사하기 위해서다. 무엇을 부르는지는
 * {@code hermes/README.md} 의 「dashboard-profile-api 가 여는 것」이 갖는다. 게시할 경로와 도구 목록을
 * 정하는 일은 {@code SkillPublisher} 가 안다.
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
     * @param sandboxOwner 셸·파일·사진 도구를 돌릴 격리 실행 공간의 주인 키. 늘 싣고, 도구 목록에 셸·파일·사진 도구가 있을 때만
     *     Hermes 가 쓴다
     * @throws HermesRequestRejected 대시보드가 4xx 로 분명히 거절했다. 설정은 바뀌지 않았다. 실행 공간이 준비되지
     *     않아 거절했으면 오류 코드가 {@code AGENT_SANDBOX_UNAVAILABLE} 이다. 도구 목록을 함께 쓰면 보내기 전에 주인의 첨부
     *     디렉터리를 만들고, 만들지 못하면 같은 코드로 보내지 않고 던진다(ADR-091)
     */
    void publish(String profile, List<String> externalDirs, List<String> apiServerToolsets, String sandboxOwner);

    /**
     * {@link #publish} 와 같되, 실행 공간에서 스크립트를 돌릴 수 없는 profile 이면 대시보드가 거절한다.
     *
     * <p>{@code scripts/} 가 든 스킬을 저장할 때 쓴다(ADR-20261009-skill-package). 도구 목록은 null 이 아니다. 본문에
     * {@code require_sandbox: true} 를 싣는다.
     *
     * @throws HermesRequestRejected 409 {@code sandbox_unavailable} 이면 {@code AGENT_SANDBOX_UNAVAILABLE} 이다. 주인의
     *     첨부 디렉터리를 만들지 못했을 때도 같은 코드로 보내지 않고 던지며, 그때 cause 는 대시보드 응답이 아니다
     */
    void publishRequiringSandbox(
            String profile, List<String> externalDirs, List<String> apiServerToolsets, String sandboxOwner);
}
