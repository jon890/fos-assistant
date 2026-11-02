package com.bifos.assistant.connector.connection.application;

import com.bifos.assistant.connector.connection.domain.ConnectorConnection;
import com.bifos.assistant.connector.connection.domain.DetachTarget;
import com.bifos.assistant.connector.connection.infra.ConnectorBindingRepository;
import com.bifos.assistant.connector.connection.infra.ConnectorConnectionRepository;
import com.bifos.assistant.hermes.application.port.HermesConnectorClient;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 사용자 정리 때 그 사용자의 연결을 지운다(ADR-20261101 / user-purge). */
@Component
@RequiredArgsConstructor
public class ConnectorConnectionPurge {

    private final ConnectorConnectionRepository connections;
    private final ConnectorBindingRepository bindings;
    private final HermesConnectorClient connector;


    /**
     * 사용자 정리 때 그 사용자의 연결을 모두 지운다(ADR-20261101 / user-purge). 로그인한 사용자가 아니라 정리 작업이 부르는 경로다.
     *
     * <p>연결마다 붙은 바인딩을 profile 에서 떼고 행을 지운 뒤 보관 파일과 연결 행을 지운다. 사용자 행은 부르는 쪽이 이미 잠갔다.
     * 엔티티를 읽지 않는다. 칸 값을 풀지 못하는 줄이 있어도 정리가 멈추지 않게 하려는 것이다. 접근이 끊긴 사용자라
     * {@link ConnectorConnectionService#disconnect} 의 소유자 잠금을 쓰지 않는다. 떼기나 보관 파일 삭제가 실패하면 예외를 그대로 던져
     * 정리 트랜잭션을 되돌린다. 이미 뗀 바인딩은 행이 없어 다시 떼지 않고, 없는 보관 파일 삭제는 거짓으로 끝나므로 다시 불러도 같다.
     *
     * @return 지운 연결 수
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int purgeUser(Long userId) {
        List<Long> owned = connections.findIdsByUserId(userId);
        for (Long connectionId : owned) {
            for (DetachTarget target : bindings.findDetachTargets(connectionId)) {
                connector.unbindConnector(target.profile(), target.connectorId());
                bindings.deleteDetached(target.bindingId());
            }
            connector.deleteVault(ConnectorConnection.vaultOf(connectionId));
            connections.deletePurged(connectionId);
        }
        return owned.size();
    }
}
