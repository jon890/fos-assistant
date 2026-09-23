package com.bifos.assistant.chat.infra;

import com.bifos.assistant.chat.domain.ChatAttachment;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatAttachmentRepository extends JpaRepository<ChatAttachment, Long> {

    /** 첨부는 언제나 대화 번호와 함께 찾는다. 번호만으로 찾으면 남의 대화의 첨부에 닿는다. */
    Optional<ChatAttachment> findByIdAndConversationId(Long id, Long conversationId);

    List<ChatAttachment> findByConversationIdAndIdIn(Long conversationId, Collection<Long> ids);

    List<ChatAttachment> findByConversationIdOrderByIdAsc(Long conversationId);

    /** 아직 메시지에 묶이지 않았고 지워지지 않은 첨부의 수. 한 번 보낼 때의 장수 상한을 이것으로 본다. */
    long countByConversationIdAndMessageIdIsNullAndDeletedAtIsNull(Long conversationId);

    List<ChatAttachment> findByExpiresAtBeforeAndDeletedAtIsNullOrderByIdAsc(Instant now);

    /**
     * 아직 묶이지 않은 보이는 첨부만 이 메시지에 묶는다.
     *
     * <p>조건을 갱신 쿼리에 두어, 두 요청이 같은 첨부를 동시에 묶으려 하면 뒤의 것은 갱신한 행 수가
     * 모자라게 된다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update ChatAttachment a
               set a.messageId = :messageId
             where a.id in :ids
               and a.conversationId = :conversationId
               and a.messageId is null
               and a.deletedAt is null
            """)
    int attachToMessage(
            @Param("messageId") Long messageId,
            @Param("conversationId") Long conversationId,
            @Param("ids") Collection<Long> ids);
}
