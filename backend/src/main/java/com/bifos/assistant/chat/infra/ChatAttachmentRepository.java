package com.bifos.assistant.chat.infra;

import com.bifos.assistant.chat.domain.ChatAttachment;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatAttachmentRepository extends JpaRepository<ChatAttachment, Long> {

    /** 첨부는 언제나 대화 번호와 함께 찾는다. 번호만으로 찾으면 남의 대화의 첨부에 닿는다. */
    Optional<ChatAttachment> findByIdAndConversationId(Long id, Long conversationId);

    List<ChatAttachment> findByConversationIdAndIdIn(Long conversationId, Collection<Long> ids);

    List<ChatAttachment> findByConversationIdOrderByIdAsc(Long conversationId);

    List<ChatAttachment> findByConversationIdOrderByMessageIdAscPositionAsc(Long conversationId);

    /** 아직 메시지에 묶이지 않았고 지워지지 않은 첨부의 수. 한 번 보낼 때의 장수 상한을 이것으로 본다. */
    long countByConversationIdAndMessageIdIsNullAndDeletedAtIsNull(Long conversationId);

    List<ChatAttachment> findByExpiresAtBeforeAndDeletedAtIsNullOrderByIdAsc(Instant now);

    /** 기동 복사는 살아 있는 파일 행만 읽는다. 계속 쌓이는 삭제 행은 조회하지 않는다. */
    Page<ChatAttachment> findByDeletedAtIsNullAndStoredNameIsNotNull(Pageable pageable);

    /** 아직 묶이지 않았고 지워지지 않은 첨부 하나를 지정한 자리로 묶는다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update ChatAttachment a
               set a.messageId = :messageId,
                   a.position = :position
             where a.id = :attachmentId
               and a.conversationId = :conversationId
               and a.messageId is null
               and a.deletedAt is null
            """)
    int attachToMessageAtPosition(
            @Param("messageId") Long messageId,
            @Param("conversationId") Long conversationId,
            @Param("attachmentId") Long attachmentId,
            @Param("position") int position);
}
