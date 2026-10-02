package com.bifos.assistant.connector.infra;

import com.bifos.assistant.connector.domain.ConnectorToolGrant;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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

    /** 선언이 상시 허락을 닫은 도구의 줄을 찾으려고 모든 사용자의 유효한 허락을 읽는다. */
    @Query("""
            select g from ConnectorToolGrant g
            where g.revokedAt is null and g.expiresAt > :now
            order by g.id
            """)
    List<ConnectorToolGrant> findActive(@Param("now") Instant now);

    /**
     * 번호의 허락을 거둔다. 그 사이 사용자가 먼저 거둔 줄은 처음 거둔 시각을 그대로 둔다.
     *
     * @return 거둔 건수
     */
    @Modifying(clearAutomatically = true)
    @Query("update ConnectorToolGrant g set g.revokedAt = :now where g.id in :ids and g.revokedAt is null")
    int revokeAll(@Param("ids") List<Long> ids, @Param("now") Instant now);
}
