package com.bifos.assistant.connector.handoff.infra;

import com.bifos.assistant.connector.action.domain.ConnectorAction;
import com.bifos.assistant.connector.action.domain.type.HandoffNeed;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** 판정 줄의 화면 넘기기 칸만 읽고 쓴다. 칸의 뜻은 {@code backend/docs/data-schema.md} 의 「connector_action」 이 갖는다. */
public interface ConnectorHandoffRepository extends Repository<ConnectorAction, Long> {

    Optional<ConnectorAction> findByDedupeKey(String dedupeKey);


    /**
     * 아직 넘기기가 없는 줄에만 적는다. hook 이 같은 호출을 다시 알려도 처음 것만 남는다.
     *
     * @return 적은 줄 수. 이미 적힌 줄이면 0
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update ConnectorAction a set a.handoff.need = :need, a.handoff.tab = :tab, a.handoff.at = :now
            where a.id = :id and a.handoff.need is null
            """)
    int record(@Param("id") Long id, @Param("need") HandoffNeed need, @Param("tab") String tab, @Param("now") Instant now);


    /** 그 사용자의 그 대화에서 닫히지 않은 넘기기다. 최신 것부터다. */
    @Query("""
            select a from ConnectorAction a
            where a.conversationId = :conversationId and a.userId = :userId
              and a.handoff.need is not null and a.handoff.closedAt is null
            order by a.handoff.at desc, a.id desc
            """)
    List<ConnectorAction> findOpen(@Param("conversationId") Long conversationId, @Param("userId") Long userId);


    /** 그 대화에서 닫히지 않은 넘기기다. 대화의 주인과 상관없이 고른다. */
    @Query("""
            select a from ConnectorAction a
            where a.conversationId = :conversationId and a.handoff.need is not null and a.handoff.closedAt is null
            """)
    List<ConnectorAction> findOpenInConversation(@Param("conversationId") Long conversationId);


    /** 열린 줄에만 닫은 시각을 적는다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update ConnectorAction a set a.handoff.closedAt = :now
            where a.id in :ids and a.handoff.closedAt is null
            """)
    int close(@Param("ids") Collection<Long> ids, @Param("now") Instant now);
}
