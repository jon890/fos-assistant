package com.bifos.assistant.notification.application;

import com.bifos.assistant.notification.infra.NotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사용자 정리 때 그 사용자의 알림을 지운다(ADR-20261101 / user-purge). 부르는 쪽의 정리 트랜잭션 안에서만 돈다.
 */
@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class NotificationUserPurge {

    private final NotificationRepository notifications;


    /**
     * 그 사용자의 알림을 모두 지운다. 없으면 0 이고 다시 불러도 같다.
     *
     * @return 지운 행 수
     */
    public int purgeUser(Long userId) {
        return notifications.deleteAllOfUser(userId);
    }
}
