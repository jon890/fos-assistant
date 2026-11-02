package com.bifos.assistant.connector.approval.application;

import com.bifos.assistant.connector.action.infra.ConnectorActionRepository;
import com.bifos.assistant.connector.connection.application.ConnectorConnectionPurge;
import com.bifos.assistant.people.application.port.UserPurgeParticipant;
import jakarta.persistence.EntityManager;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사용자 정리 때 커넥터 쪽을 맡는다(ADR-20261101 / user-purge).
 *
 * <p>승인 줄(실행 줄과 그 줄이 준 허락 포함)을 지운 뒤 연결을 지운다. 연결은 바인딩을 profile 에서 떼고 보관 파일까지 지운다. 모두
 * 사용자 줄을 {@code ON DELETE CASCADE} 없이 가리키므로 사용자 줄보다 먼저 지운다. 부르는 쪽의 정리 트랜잭션 안에서만 돈다.
 *
 * <p>옛 허락 테이블 {@code connector_tool_grant}, {@code connector_conversation_grant} 는 코드가 읽고 쓰지 않지만, 직전 이미지가
 * 남긴 그 사용자의 줄을 정리 약속대로 native 삭제로 지운다. 「코드가 읽고 쓰지 않는다」 의 유일한 예외이고 옛 테이블을 지우는
 * PR 이 이 삭제를 걷어 낸다(ADR-20261102 / table-reduce-r2).
 */
@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
class ConnectorUserPurge implements UserPurgeParticipant {

    private final ConnectorActionRepository actions;
    private final ConnectorConnectionPurge connections;
    private final EntityManager entityManager;


    @Override
    public void purgeUser(Long userId) {
        deleteLegacyGrants(userId);
        actions.deleteAllOfUser(userId);
        connections.purgeUser(userId);
    }


    /** 옛 허락 테이블에서 그 사용자의 줄을 지운다. 표 이름은 고정 문자열이다. */
    private void deleteLegacyGrants(Long userId) {
        for (String table : List.of("connector_conversation_grant", "connector_tool_grant")) {
            entityManager.createNativeQuery("delete from " + table + " where user_id = :userId")
                .setParameter("userId", userId)
                .executeUpdate();
        }
    }
}
