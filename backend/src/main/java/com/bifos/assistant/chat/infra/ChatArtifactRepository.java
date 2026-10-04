package com.bifos.assistant.chat.infra;

import com.bifos.assistant.chat.domain.ChatArtifact;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatArtifactRepository extends JpaRepository<ChatArtifact, Long> {

    /** 대화 이력을 열 때 답마다 묻지 않고 한 번에 읽는다. */
    List<ChatArtifact> findByMessageIdInOrderByIdAsc(Collection<Long> messageIds);

    List<ChatArtifact> findByMessageId(Long messageId);

    /** 없는 파일이 보관 기간이 지나 지워진 것인지 가릴 때만 쓴다. */
    boolean existsByConversationIdAndPathAndDeletedAtIsNotNull(Long conversationId, String path);

    /**
     * 그 파일을 가리키는 행 모두에 지운 시각을 적는다. 같은 파일이 여러 답에 묶였으면 모두 적는다.
     *
     * <p>이미 적힌 행은 처음 시각을 그대로 둔다.
     *
     * <p>{@code createdBefore} 보다 앞서 만든 행에만 적는다. 같은 경로에 파일을 새로 쓰면 새 행이 생기는데, 그 행은
     * 지운 파일이 아니라 새 파일을 가리키므로 건드리지 않는다.
     *
     * <p>트랜잭션은 {@code ChatArtifactWriter} 가 연다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update ChatArtifact a
               set a.deletedAt = :now
             where a.conversationId = :conversationId
               and a.path = :path
               and a.deletedAt is null
               and a.createdAt < :createdBefore
            """)
    int markDeleted(
            @Param("conversationId") Long conversationId,
            @Param("path") String path,
            @Param("now") Instant now,
            @Param("createdBefore") Instant createdBefore);

    /** 지운 표시가 없고 {@code createdBefore} 보다 앞서 만든 행이다. 파일이 없는데 행이 살아 있는 것을 찾을 때 쓴다. */
    List<ChatArtifact> findByDeletedAtIsNullAndCreatedAtBefore(Instant createdBefore);
}
