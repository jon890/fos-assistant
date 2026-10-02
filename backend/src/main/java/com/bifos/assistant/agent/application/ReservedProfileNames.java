package com.bifos.assistant.agent.application;

/**
 * 에이전트 표 밖에서 이미 쥐고 있는 profile 이름을 알려 준다. 허용 목록을 가진 {@code people} 이 구현한다.
 *
 * <p>{@code agent} 는 {@code people} 보다 아래 패키지라 허용 목록을 직접 읽지 못한다(ADR-068).
 */
public interface ReservedProfileNames {

    /** 허용 목록의 누군가가 그 profile 이름을 쓰고 있는가. */
    boolean reservedByPerson(String profileName);
}
