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
    /** 엔티티 캐시와 관계없이 보낸 원본의 소유·보관 상태를 다시 확인한다. */
    boolean existsByIdAndConversationIdAndUploadedByUserIdAndMessageIdIsNotNullAndDeletedAtIsNullAndExpiresAtAfter(
            Long id, Long conversationId, Long uploadedByUserId, Instant now);

    /** 첨부는 언제나 대화 번호와 함께 찾는다. 번호만으로 찾으면 남의 대화의 첨부에 닿는다. */
    Optional<ChatAttachment> findByIdAndConversationId(Long id, Long conversationId);

    List<ChatAttachment> findByConversationIdAndIdIn(Long conversationId, Collection<Long> ids);

    List<ChatAttachment> findByConversationIdOrderByIdAsc(Long conversationId);

    List<ChatAttachment> findByConversationIdOrderByMessageIdAscPositionAsc(Long conversationId);

    /** 아직 메시지에 묶이지 않았고 지워지지 않은 첨부의 수. 한 번 보낼 때의 장수 상한을 이것으로 본다. */
    long countByConversationIdAndMessageIdIsNullAndDeletedAtIsNull(Long conversationId);

    List<ChatAttachment> findByExpiresAtBeforeAndDeletedAtIsNullOrderByIdAsc(Instant now);

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

    /** 그 대화의 첨부 행을 모두 지운다. 파일은 부르는 쪽이 먼저 지운다. 트랜잭션은 {@code ConversationPurgeWriter} 가 연다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from ChatAttachment a where a.conversationId = :conversationId")
    int deleteAllOf(@Param("conversationId") Long conversationId);
}
