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
 * 대화 허락을 준 승인 줄을 허락으로 읽고 거둔다(ADR-20261012 / conversation-grant, ADR-20261102 / table-reduce-r2). 허락의 번호는
 * 그 승인 줄의 번호다.
 *
 * <p>유효한 대화 허락은 범위가 {@code CONVERSATION} 이고 거두지 않았고 기간이 남았고 그 대화가 지워지지 않은 줄이다. 지운 에이전트를
 * 정리해 에이전트가 빈 줄은 어느 실행에도 통하지 않는다. 거두기는 허락 칸만 바꾸는 갱신으로 한다.
 */
public interface ConnectorConversationGrantRepository extends Repository<ConnectorAction, Long> {

    @Query("""
            select count(a) > 0 from ConnectorAction a
            where a.grant.scope = CONVERSATION
              and a.userId = :userId and a.conversationId = :conversationId and a.agentId = :agentId
              and a.connectorId = :connectorId and a.toolName = :toolName
              and a.grant.revokedAt is null and a.grant.expiresAt > :now
              and exists (select c.id from Conversation c where c.id = a.conversationId and c.deletedAt is null)
            """)
    boolean existsActive(
        @Param("userId") Long userId,
        @Param("conversationId") Long conversationId,
        @Param("agentId") Long agentId,
        @Param("connectorId") String connectorId,
        @Param("toolName") String toolName,
        @Param("now") Instant now);


    /** 그 대화에서 사용자가 준 유효한 허락이다. 승인 영역의 「계속 허용 중」 줄이 읽는다. 어느 실행에도 통하지 않는 줄은 내지 않는다. */
    @Query("""
            select a from ConnectorAction a
            where a.grant.scope = CONVERSATION
              and a.userId = :userId and a.conversationId = :conversationId and a.agentId is not null
              and a.grant.revokedAt is null and a.grant.expiresAt > :now
            order by a.id
            """)
    List<ConnectorAction> findActiveInConversation(
        @Param("userId") Long userId, @Param("conversationId") Long conversationId, @Param("now") Instant now);


    /** 선언이 대화 허락을 닫은 도구의 줄을 찾으려고 모든 사용자의 유효한 대화 허락을 읽는다. */
    @Query("""
            select a from ConnectorAction a
            where a.grant.scope = CONVERSATION
              and a.grant.revokedAt is null and a.grant.expiresAt > :now
            order by a.id
            """)
    List<ConnectorAction> findActive(@Param("now") Instant now);


    /** 그 사용자가 그 대화에서 준 대화 허락을 번호로 찾는다. 남의 허락과 다른 대화의 허락, 허락이 없는 줄은 없는 것이다. */
    @Query("""
            select a from ConnectorAction a
            where a.id = :id and a.userId = :userId and a.conversationId = :conversationId
              and a.grant.scope = CONVERSATION
            """)
    Optional<ConnectorAction> findOwnInConversation(
        @Param("id") Long id, @Param("userId") Long userId, @Param("conversationId") Long conversationId);


    /**
     * 번호의 대화 허락을 거둔다. 그 사이 먼저 거둔 줄은 처음 거둔 시각을 그대로 둔다.
     *
     * @return 거둔 건수
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            update ConnectorAction a set a.grant.revokedAt = :now
            where a.id in :ids
              and a.grant.scope = CONVERSATION
              and a.grant.revokedAt is null
            """)
    int revokeAll(@Param("ids") List<Long> ids, @Param("now") Instant now);


    /**
     * 그 대화의 그 에이전트에 준 그 도구의 대화 허락을 모두 거둔다. 같은 도구에 새 허락을 줄 때와 사용자가 「다시 묻기」 를 누를 때
     * 부른다. 같은 열쇠의 유효한 줄이 둘 남아 하나만 거둬지는 일이 없게 한다.
     *
     * @return 거둔 건수
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            update ConnectorAction a set a.grant.revokedAt = :now
            where a.grant.scope = CONVERSATION
              and a.userId = :userId and a.conversationId = :conversationId and a.agentId = :agentId
              and a.connectorId = :connectorId and a.toolName = :toolName and a.grant.revokedAt is null
            """)
    int revokeAllOfTool(
        @Param("userId") Long userId,
        @Param("conversationId") Long conversationId,
        @Param("agentId") Long agentId,
        @Param("connectorId") String connectorId,
        @Param("toolName") String toolName,
        @Param("now") Instant now);


    /**
     * 그 사용자의 그 플러그인에 준 대화 허락을 모두 거둔다. 연결을 해제하거나 값을 다시 등록할 때 부른다.
     *
     * @return 거둔 건수
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            update ConnectorAction a set a.grant.revokedAt = :now
            where a.grant.scope = CONVERSATION
              and a.userId = :userId and a.connectorId = :connectorId and a.grant.revokedAt is null
            """)
    int revokeAllOfConnector(
        @Param("userId") Long userId, @Param("connectorId") String connectorId, @Param("now") Instant now);


    /**
     * 그 에이전트에 준 그 플러그인의 대화 허락을 거둔다. 그 에이전트에서 연결을 뗄 때 부른다.
     *
     * @return 거둔 건수
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            update ConnectorAction a set a.grant.revokedAt = :now
            where a.grant.scope = CONVERSATION
              and a.userId = :userId and a.connectorId = :connectorId and a.agentId = :agentId
              and a.grant.revokedAt is null
            """)
    int revokeAllOfAgent(
        @Param("userId") Long userId,
        @Param("connectorId") String connectorId,
        @Param("agentId") Long agentId,
        @Param("now") Instant now);
}
