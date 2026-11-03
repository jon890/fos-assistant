package com.bifos.assistant.connector.approval.application;

import com.bifos.assistant.connector.action.infra.ConnectorActionRepository;
import com.bifos.assistant.connector.connection.application.ConnectorConnectionPurge;
import com.bifos.assistant.people.application.port.UserPurgeParticipant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사용자 정리 때 커넥터 쪽을 맡는다(ADR-20261101 / user-purge).
 *
 * <p>승인 줄(실행 줄과 그 줄이 준 허락 포함)을 지운 뒤 연결을 지운다. 연결은 바인딩을 profile 에서 떼고 보관 파일까지 지운다. 모두
 * 사용자 줄을 {@code ON DELETE CASCADE} 없이 가리키므로 사용자 줄보다 먼저 지운다. 부르는 쪽의 정리 트랜잭션 안에서만 돈다.
 */
@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
class ConnectorUserPurge implements UserPurgeParticipant {

    private final ConnectorActionRepository actions;
    private final ConnectorConnectionPurge connections;


    @Override
    public void purgeUser(Long userId) {
        actions.deleteAllOfUser(userId);
        connections.purgeUser(userId);
    }
}
