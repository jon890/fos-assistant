package com.bifos.assistant.connector.infra;

import com.bifos.assistant.connector.domain.ConnectorToolGrant;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 유효한 허락은 거두지 않았고 기간이 남은 줄이다. */
public interface ConnectorToolGrantRepository extends JpaRepository<ConnectorToolGrant, Long> {

    @Query("""
            select count(g) > 0 from ConnectorToolGrant g
            where g.userId = :userId and g.connectorId = :connectorId and g.toolName = :toolName
              and g.revokedAt is null and g.expiresAt > :now
            """)
    boolean existsActive(
            @Param("userId") Long userId,
            @Param("connectorId") String connectorId,
            @Param("toolName") String toolName,
            @Param("now") Instant now);

    @Query("""
            select g from ConnectorToolGrant g
            where g.userId = :userId and g.revokedAt is null and g.expiresAt > :now
            order by g.id
            """)
    List<ConnectorToolGrant> findActiveByUserId(@Param("userId") Long userId, @Param("now") Instant now);

    /** 연결을 해제하거나 값을 다시 등록할 때 그 연결의 허락을 거두려고 읽는다. */
    @Query("""
            select g from ConnectorToolGrant g
            where g.userId = :userId and g.connectorId = :connectorId
              and g.revokedAt is null and g.expiresAt > :now
            order by g.id
            """)
    List<ConnectorToolGrant> findActiveByUserIdAndConnectorId(
            @Param("userId") Long userId, @Param("connectorId") String connectorId, @Param("now") Instant now);
}
