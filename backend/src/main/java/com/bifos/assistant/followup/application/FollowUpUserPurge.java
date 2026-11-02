package com.bifos.assistant.followup.application;

import com.bifos.assistant.followup.infra.FollowUpRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사용자 정리 때 그 사용자의 할 일을 지운다(ADR-20261101 / user-purge). 부르는 쪽의 정리 트랜잭션 안에서만 돈다.
 */
@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class FollowUpUserPurge {

    private final FollowUpRepository followUps;


    /**
     * 그 사용자의 할 일을 모두 지운다. 없으면 0 이고 다시 불러도 같다.
     *
     * @return 지운 행 수
     */
    public int purgeUser(Long userId) {
        return followUps.deleteAllOfUser(userId);
    }
}
