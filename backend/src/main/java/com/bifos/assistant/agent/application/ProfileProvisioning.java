package com.bifos.assistant.agent.application;

/**
 * 에이전트가 쓸 Hermes profile 을 만들고 거둔다. 순서와 토큰 발급을 아는 {@code people} 이 구현한다.
 *
 * <p>{@code agent} 는 {@code people} 보다 아래 패키지라 그 구현을 직접 쓰지 못한다(ADR-068).
 */
public interface ProfileProvisioning {

    /** profile 을 끝까지 만든다. 중간에 실패하면 만든 것을 되돌리고 그 오류를 던진다. */
    void provision(String profileName);

    /** Control Plane 이 만든 profile 을 거둔다. 하나라도 실패하면 그 오류를 그대로 던진다. */
    void deprovision(String profileName);
}
