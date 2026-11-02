package com.bifos.assistant.connector.connection.infra;

import com.bifos.assistant.connector.connection.domain.ConnectorConnection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ConnectorConnectionRepository extends JpaRepository<ConnectorConnection, Long> {

    Optional<ConnectorConnection> findByUserIdAndConnectorId(Long userId, String connectorId);


    List<ConnectorConnection> findByUserId(Long userId);


    List<ConnectorConnection> findByUserIdIn(List<Long> userIds);


    /** 사용자 정리 때 그 사용자의 연결 번호를 읽는다. 칸 값을 풀지 않으려고 엔티티를 읽지 않는다. */
    @Query("select c.id from ConnectorConnection c where c.userId = :userId order by c.id")
    List<Long> findIdsByUserId(@Param("userId") Long userId);


    /** 사용자 정리 때 바인딩을 모두 지운 연결 행 하나를 지운다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from ConnectorConnection c where c.id = :id")
    int deletePurged(@Param("id") Long id);
}
