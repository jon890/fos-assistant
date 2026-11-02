package com.bifos.assistant.people.application.port;

/**
 * 사용자를 정리할 때 그 사용자를 가리키는 자기 표의 줄을 맡는다(ADR-20261101 / user-purge).
 *
 * <p>{@code people} 보다 위 패키지가 구현한다. {@code people} 은 그 패키지를 import 하지 못하므로 여기 port 를 두고
 * {@code UserPurgeWriter} 가 구현 모두를 개인 데이터 단계의 트랜잭션 안에서 부른다. 아래 패키지의 표는 그 패키지의 정리 빈을 직접
 * 부른다.
 */
public interface UserPurgeParticipant {

    /**
     * 그 사용자의 줄을 지우거나 그 사용자를 가리키는 칸을 비운다. 정리 트랜잭션 안에서 부른다.
     *
     * <p>이미 지운 것을 다시 지워도 같은 결과여야 한다. 실패하면 예외를 던지고, 정리는 다음 차례에 이 단계부터 다시 한다.
     */
    void purgeUser(Long userId);
}
