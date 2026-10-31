package com.bifos.assistant.chat.artifact.infra;

import com.bifos.assistant.chat.artifact.domain.ChatArtifact;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatArtifactRepository extends JpaRepository<ChatArtifact, Long> {

    /** 대화 이력을 열 때 답마다 묻지 않고 한 번에 읽는다. */
    List<ChatArtifact> findByMessageIdInOrderByIdAsc(Collection<Long> messageIds);


    List<ChatArtifact> findByMessageId(Long messageId);


    /** 없는 파일이 보관 기간이 지나 지워진 것인지 가릴 때만 쓴다. */
    boolean existsByConversationIdAndPathAndDeletedAtIsNotNullAndVaultUserIdIsNull(Long conversationId, String path);


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
               and a.vaultUserId is null
               and a.createdAt < :createdBefore
            """)
    int markDeleted(
        @Param("conversationId") Long conversationId,
        @Param("path") String path,
        @Param("now") Instant now,
        @Param("createdBefore") Instant createdBefore);


    /** 지운 표시가 없고 {@code createdBefore} 보다 앞서 만든 행이다. 파일이 없는데 행이 살아 있는 것을 찾을 때 쓴다. */
    List<ChatArtifact> findByDeletedAtIsNullAndVaultUserIdIsNullAndCreatedAtBefore(Instant createdBefore);


    /**
     * 그 대화의 결과물 행을 모두 지운다. 파일은 부르는 쪽이 먼저 지운다. 트랜잭션은 {@code ConversationPurgeWriter} 가 연다.
     *
     * <p>사용자가 보관한 사본({@code vault_user_id} 가 채워진 행)은 남긴다. 보관본은 대화와 독립된 수명을 가진다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from ChatArtifact a where a.conversationId = :conversationId and a.vaultUserId is null")
    int deleteAllOf(@Param("conversationId") Long conversationId);


    /** 그 사용자가 보관한 사본의 평문 바이트 수 합이다. 용량을 넘는지 볼 때 쓴다. */
    @Query("select coalesce(sum(a.byteSize), 0) from ChatArtifact a where a.vaultUserId = :userId")
    long sumVaultBytes(@Param("userId") Long userId);


    /**
     * 그 사용자의 보관본을 새로 만든 것부터 준다. 묶음은 뿌리 행 하나로 보이고 안의 파일 수와 바이트를 더한다.
     *
     * <p>암호화한 본문은 목록에 필요 없어 읽지 않는다.
     */
    @Query("""
            select a.id as id, a.path as path, a.byteSize as byteSize, a.createdAt as createdAt,
                   (select count(p.id) from ChatArtifact p where p.vaultBundleId = a.id) as partCount,
                   (select coalesce(sum(p.byteSize), 0) from ChatArtifact p where p.vaultBundleId = a.id) as partBytes
              from ChatArtifact a
             where a.vaultUserId = :userId
               and a.vaultBundleId is null
             order by a.id desc
            """)
    List<ArtifactVaultRow> listVaulted(@Param("userId") Long userId);


    Optional<ChatArtifact> findByIdAndVaultUserId(Long id, Long vaultUserId);


    /** 주인의 보관본 가운데 뿌리 행이다. 묶음 안 파일의 번호로는 찾지 못한다. */
    Optional<ChatArtifact> findByIdAndVaultUserIdAndVaultBundleIdIsNull(Long id, Long vaultUserId);


    /** 주인의 묶음 안에서 그 경로의 파일이다. 주인을 다시 확인한다. */
    Optional<ChatArtifact> findByVaultBundleIdAndPathAndVaultUserId(Long vaultBundleId, String path, Long vaultUserId);


    /** 주인의 묶음 안 파일의 번호와 경로를 경로 순으로 준다. 본문은 읽지 않는다. */
    @Query("""
            select p.id as id, p.path as path
              from ChatArtifact p
             where p.vaultBundleId = :bundleId
               and p.vaultUserId = :userId
             order by p.path asc
            """)
    List<ArtifactVaultPart> listParts(@Param("bundleId") Long bundleId, @Param("userId") Long userId);


    Optional<ChatArtifact> findByVaultUserIdAndVaultFingerprint(Long vaultUserId, String vaultFingerprint);


    /**
     * 주인의 보관본 하나를 지운다. 묶음이면 안의 파일도 함께 지운다. 묶음 안 파일의 번호로는 지우지 못한다.
     *
     * <p>지운 줄 수가 0 이면 없거나 남의 것이다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            delete from ChatArtifact a
             where a.vaultUserId = :userId
               and ((a.id = :id and a.vaultBundleId is null) or a.vaultBundleId = :id)
            """)
    int deleteVaulted(@Param("id") Long id, @Param("userId") Long userId);
}
