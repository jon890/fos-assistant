package com.bifos.assistant.connector.infra;

import com.bifos.assistant.connector.domain.ConnectorConversationGrant;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 유효한 대화 허락은 거두지 않았고 기간이 남았고 그 대화가 지워지지 않은 줄이다. */
public interface ConnectorConversationGrantRepository extends JpaRepository<ConnectorConversationGrant, Long> {

    @Query("""
            select count(g) > 0 from ConnectorConversationGrant g
            where g.userId = :userId and g.conversationId = :conversationId and g.agentId = :agentId
              and g.connectorId = :connectorId and g.toolName = :toolName
              and g.revokedAt is null and g.expiresAt > :now
              and exists (select c.id from Conversation c where c.id = g.conversationId and c.deletedAt is null)
            """)
    boolean existsActive(
        @Param("userId") Long userId,
        @Param("conversationId") Long conversationId,
        @Param("agentId") Long agentId,
        @Param("connectorId") String connectorId,
        @Param("toolName") String toolName,
        @Param("now") Instant now);


    /** 그 대화에서 사용자가 준 유효한 허락이다. 승인 영역의 「계속 허용 중」 줄이 읽는다. */
    @Query("""
            select g from ConnectorConversationGrant g
            where g.userId = :userId and g.conversationId = :conversationId
              and g.revokedAt is null and g.expiresAt > :now
            order by g.id
            """)
    List<ConnectorConversationGrant> findActiveInConversation(
        @Param("userId") Long userId, @Param("conversationId") Long conversationId, @Param("now") Instant now);


    /** 선언이 대화 허락을 닫은 도구의 줄을 찾으려고 모든 사용자의 유효한 대화 허락을 읽는다. */
    @Query("""
            select g from ConnectorConversationGrant g
            where g.revokedAt is null and g.expiresAt > :now
            order by g.id
            """)
    List<ConnectorConversationGrant> findActive(@Param("now") Instant now);


    /**
     * 그 승인 줄과 함께 준 대화 허락을 거둔다. 그 실행이 실패했거나 결과를 모를 때 부른다.
     *
     * @return 거둔 건수
     */
    @Modifying(flushAutomatically = true)
    @Query("update ConnectorConversationGrant g set g.revokedAt = :now where g.actionId = :actionId and g.revokedAt is null")
    int revokeOfAction(@Param("actionId") Long actionId, @Param("now") Instant now);


    /**
     * 번호의 대화 허락을 거둔다. 그 사이 사용자가 먼저 거둔 줄은 처음 거둔 시각을 그대로 둔다.
     *
     * @return 거둔 건수
     */
    @Modifying(clearAutomatically = true)
    @Query("update ConnectorConversationGrant g set g.revokedAt = :now where g.id in :ids and g.revokedAt is null")
    int revokeAll(@Param("ids") List<Long> ids, @Param("now") Instant now);


    /**
     * 그 승인 줄과 함께 준 허락이 아직 유효한가. 결과의 알림 줄이 허락을 함께 알릴지 정한다. 다시 전할 때 이미 거두었거나 기간이
     * 지난 허락을 「다시 묻지 않고 실행해요」 로 알리지 않는다.
     */
    @Query("""
            select count(g) > 0 from ConnectorConversationGrant g
            where g.actionId = :actionId and g.revokedAt is null and g.expiresAt > :now
            """)
    boolean existsActiveByActionId(@Param("actionId") Long actionId, @Param("now") Instant now);


    /**
     * 그 대화의 그 에이전트에 준 그 도구의 대화 허락을 모두 거둔다. 같은 도구에 새 허락을 줄 때와 사용자가 「다시 묻기」 를 누를 때
     * 부른다. 같은 열쇠의 유효한 줄이 둘 남아 하나만 거둬지는 일이 없게 한다.
     *
     * @return 거둔 건수
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            update ConnectorConversationGrant g set g.revokedAt = :now
            where g.userId = :userId and g.conversationId = :conversationId and g.agentId = :agentId
              and g.connectorId = :connectorId and g.toolName = :toolName and g.revokedAt is null
            """)
    int revokeAllOfTool(
        @Param("userId") Long userId,
        @Param("conversationId") Long conversationId,
        @Param("agentId") Long agentId,
        @Param("connectorId") String connectorId,
        @Param("toolName") String toolName,
        @Param("now") Instant now);


    /**
     * 그 사용자의 그 커넥터에 준 대화 허락을 모두 거둔다. 연결을 해제하거나 값을 다시 등록할 때 부른다.
     *
     * @return 거둔 건수
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            update ConnectorConversationGrant g set g.revokedAt = :now
            where g.userId = :userId and g.connectorId = :connectorId and g.revokedAt is null
            """)
    int revokeAllOfConnector(
        @Param("userId") Long userId, @Param("connectorId") String connectorId, @Param("now") Instant now);


    /**
     * 그 에이전트에 준 그 커넥터의 대화 허락을 거둔다. 그 에이전트에서 연결을 뗄 때 부른다.
     *
     * @return 거둔 건수
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            update ConnectorConversationGrant g set g.revokedAt = :now
            where g.userId = :userId and g.connectorId = :connectorId and g.agentId = :agentId
              and g.revokedAt is null
            """)
    int revokeAllOfAgent(
        @Param("userId") Long userId,
        @Param("connectorId") String connectorId,
        @Param("agentId") Long agentId,
        @Param("now") Instant now);
}
