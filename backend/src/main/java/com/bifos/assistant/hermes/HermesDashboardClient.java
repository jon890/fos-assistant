package com.bifos.assistant.hermes;

/**
 * Hermes 대시보드의 profile 관리 API 를 부른다.
 *
 * <p>인터페이스로 둔 것은 실제 Hermes 없이 Control Plane 을 검사하기 위해서다. 무엇을 부르는지는
 * {@code docs/hermes-integration.md} 의 「profile 을 HTTP 로 만드는 길」이 갖는다.
 *
 * <p>여기서 profile 을 만드는 것까지만 한다. 만드는 순서와 실패했을 때 되돌리는 일은 {@code
 * HermesProfileProvisioner} 가 안다.
 */
public interface HermesDashboardClient {

    /**
     * profile 을 만든다.
     *
     * <p>본뜰 profile 을 주지 않는다. 주면 본뜬 profile 의 {@code API_SERVER_KEY} 까지 복사되어 key
     * 하나로 두 profile 이 열린다.
     */
    void createProfile(String name);

    /** 그 profile 의 {@code .env} 에 값 하나를 쓴다. */
    void putEnv(String profile, String key, String value);

    /**
     * profile 을 지운다.
     *
     * <p>만들다 실패해 되돌릴 때만 부른다. 대시보드에서 이 경로는 그 이름마다 따로 열어야 해서, 여는
     * 일은 Hermes 쪽 plugin 이 맡는다.
     */
    void deleteProfile(String name);
}
