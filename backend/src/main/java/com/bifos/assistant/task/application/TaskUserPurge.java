package com.bifos.assistant.task.application;

import com.bifos.assistant.people.application.port.UserPurgeParticipant;
import com.bifos.assistant.task.infra.TaskRepository;
import com.bifos.assistant.task.infra.TaskRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사용자 정리 때 그 사용자의 예약 작업과 실행 이력을 지운다(ADR-20261101 / user-purge).
 *
 * <p>실행 이력이 작업을 외래 키로 가리키므로 이력을 먼저 지운다. 부르는 쪽의 정리 트랜잭션 안에서만 돈다.
 */
@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
class TaskUserPurge implements UserPurgeParticipant {

    private final TaskRepository tasks;
    private final TaskRunRepository runs;


    @Override
    public void purgeUser(Long userId) {
        runs.deleteAllOfOwner(userId);
        tasks.deleteAllOfOwner(userId);
    }
}
