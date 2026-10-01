package com.bifos.assistant.chat.infra;

import com.bifos.assistant.chat.domain.ChatPendingMessage;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 트랜잭션은 부르는 서비스가 연다. 고치는 쿼리는 트랜잭션 밖에서 부르면 실패한다. */
public interface ChatPendingMessageRepository extends JpaRepository<ChatPendingMessage, Long> {

    /** 쌓인 순서로 읽는다. */
    List<ChatPendingMessage> findByConversationIdOrderByIdAsc(Long conversationId);

    /** 지운 행 수를 돌려준다. 빈 목록으로 부르지 않는다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from ChatPendingMessage p where p.id in :ids")
    int deleteAllByIdIn(@Param("ids") List<Long> ids);

    /** 대화 번호가 맞는 행만 지운다. 번호만으로 지우면 남의 대화의 대기 글에 닿는다. 지운 행 수를 돌려준다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from ChatPendingMessage p where p.id = :id and p.conversationId = :conversationId")
    int deleteOne(@Param("id") Long id, @Param("conversationId") Long conversationId);

    /** 대화를 지울 때 그 대화의 대기 줄을 모두 지운다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from ChatPendingMessage p where p.conversationId = :conversationId")
    int deleteAllOf(@Param("conversationId") Long conversationId);

    /** 대화의 멈춤을 한 번에 세우거나 내린다. 바뀐 행 수를 돌려준다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update ChatPendingMessage p set p.held = :held"
            + " where p.conversationId = :conversationId and p.held <> :held")
    int markHeld(@Param("conversationId") Long conversationId, @Param("held") boolean held);

    /** 멈춘 행이 하나도 없는 대기 줄을 가진 대화의 번호다. */
    @Query("select distinct p.conversationId from ChatPendingMessage p"
            + " where p.conversationId not in"
            + " (select h.conversationId from ChatPendingMessage h where h.held = true)")
    List<Long> findConversationsReadyToSend();
}
