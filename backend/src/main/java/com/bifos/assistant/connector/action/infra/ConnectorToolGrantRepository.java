package com.bifos.assistant.connector.action.infra;

import com.bifos.assistant.connector.action.domain.ConnectorAction;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * 상시 허락을 준 승인 줄을 허락으로 읽고 거둔다(ADR-050, ADR-20261102 / table-reduce-r2). 허락의 번호는 그 승인 줄의 번호다.
 *
 * <p>유효한 허락은 범위가 {@code TOOL} 이고 거두지 않았고 기간이 남은 줄이다. 거두기는 허락 칸만 바꾸는 갱신으로 한다. 같은 줄을
 * 다른 트랜잭션이 엔티티로 고쳐도 그 갱신이 허락 칸을 덮어쓰지 않는다({@code ConnectorAction} 의 {@code @DynamicUpdate}).
 */
public interface ConnectorToolGrantRepository extends Repository<ConnectorAction, Long> {

    @Query("""
            select count(a) > 0 from ConnectorAction a
            where a.grant.scope = TOOL
              and a.userId = :userId and a.connectorId = :connectorId and a.toolName = :toolName
              and a.grant.revokedAt is null and a.grant.expiresAt > :now
            """)
    boolean existsActive(
        @Param("userId") Long userId,
        @Param("connectorId") String connectorId,
        @Param("toolName") String toolName,
        @Param("now") Instant now);


    @Query("""
            select a from ConnectorAction a
            where a.grant.scope = TOOL
              and a.userId = :userId and a.grant.revokedAt is null and a.grant.expiresAt > :now
            order by a.id
            """)
    List<ConnectorAction> findActiveByUserId(@Param("userId") Long userId, @Param("now") Instant now);


    /** 연결 확인이 받은 도구 목록으로 거둘 허락을 고르려고 읽는다. */
    @Query("""
            select a from ConnectorAction a
            where a.grant.scope = TOOL
              and a.userId = :userId and a.connectorId = :connectorId
              and a.grant.revokedAt is null and a.grant.expiresAt > :now
            order by a.id
            """)
    List<ConnectorAction> findActiveByUserIdAndConnectorId(
        @Param("userId") Long userId, @Param("connectorId") String connectorId, @Param("now") Instant now);


    /** 선언이 상시 허락을 닫은 도구의 줄을 찾으려고 모든 사용자의 유효한 허락을 읽는다. */
    @Query("""
            select a from ConnectorAction a
            where a.grant.scope = TOOL
              and a.grant.revokedAt is null and a.grant.expiresAt > :now
            order by a.id
            """)
    List<ConnectorAction> findActive(@Param("now") Instant now);


    /** 그 사용자의 상시 허락을 번호로 찾는다. 기간이 지났거나 거둔 허락도 찾는다. 남의 허락과 허락이 없는 줄은 없는 것이다. */
    @Query("""
            select a from ConnectorAction a
            where a.id = :id and a.userId = :userId
              and a.grant.scope = TOOL
            """)
    Optional<ConnectorAction> findOwn(@Param("id") Long id, @Param("userId") Long userId);


    /**
     * 번호의 허락을 거둔다. 그 사이 먼저 거둔 줄은 처음 거둔 시각을 그대로 둔다.
     *
     * @return 거둔 건수
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            update ConnectorAction a set a.grant.revokedAt = :now
            where a.id in :ids and a.grant.scope = TOOL
              and a.grant.revokedAt is null
            """)
    int revokeAll(@Param("ids") List<Long> ids, @Param("now") Instant now);


    /**
     * 그 사용자의 그 플러그인에 준 상시 허락을 모두 거둔다. 연결을 해제하거나 값을 다시 등록할 때 부른다.
     *
     * @return 거둔 건수
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            update ConnectorAction a set a.grant.revokedAt = :now
            where a.grant.scope = TOOL
              and a.userId = :userId and a.connectorId = :connectorId and a.grant.revokedAt is null
            """)
    int revokeAllOfConnector(
        @Param("userId") Long userId, @Param("connectorId") String connectorId, @Param("now") Instant now);


    /**
     * 그 사용자의 그 플러그인 그 도구에 준 상시 허락을 모두 거둔다. 승인 실행이 읽기 표시를 거둔 도구에 부른다.
     *
     * @return 거둔 건수
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            update ConnectorAction a set a.grant.revokedAt = :now
            where a.grant.scope = TOOL
              and a.userId = :userId and a.connectorId = :connectorId and a.toolName = :toolName
              and a.grant.revokedAt is null
            """)
    int revokeAllOfTool(
        @Param("userId") Long userId,
        @Param("connectorId") String connectorId,
        @Param("toolName") String toolName,
        @Param("now") Instant now);
}
