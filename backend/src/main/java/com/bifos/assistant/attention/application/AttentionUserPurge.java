package com.bifos.assistant.attention.application;

import com.bifos.assistant.attention.infra.AttentionEventRepository;
import com.bifos.assistant.people.application.port.UserPurgeParticipant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사용자 정리 때 그 사용자의 주의 사건과 카드 제어를 지운다(ADR-20261101 / user-purge).
 *
 * <p>두 표는 사용자 줄을 {@code ON DELETE CASCADE} 없이 가리키므로 사용자 줄보다 먼저 지운다. 부르는 쪽의 정리 트랜잭션 안에서만 돈다.
 */
@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
class AttentionUserPurge implements UserPurgeParticipant {

    private final AttentionEventRepository events;


    @Override
    public void purgeUser(Long userId) {
        events.deleteAllOfUser(userId);
        events.deleteAllControlsOfUser(userId);
    }
}
