package com.bifos.assistant.chat.artifact.infra;

import com.bifos.assistant.chat.artifact.domain.ChatArtifact;
import com.bifos.assistant.chat.artifact.domain.type.AutoVaultState;
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
     * 그 대화의 지우기 이력({@code deleted_at} 이 채워진 보관 뿌리 행)은 함께 지운다. 지운 대화에서는 자동 보관이 다시 돌지 않는다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            delete from ChatArtifact a
             where a.conversationId = :conversationId
               and (a.vaultUserId is null or a.deletedAt is not null)
            """)
    int deleteAllOf(@Param("conversationId") Long conversationId);


    /** 그 사용자가 보관한 사본의 평문 바이트 수 합이다. 용량을 넘는지 볼 때 쓴다. 지운 보관본의 이력은 세지 않는다. */
    @Query("select coalesce(sum(a.byteSize), 0) from ChatArtifact a where a.vaultUserId = :userId and a.deletedAt is null")
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
               and a.deletedAt is null
             order by a.id desc
            """)
    List<ArtifactVaultRow> listVaulted(@Param("userId") Long userId);


    Optional<ChatArtifact> findByIdAndVaultUserId(Long id, Long vaultUserId);


    /** 주인의 보관본 가운데 지우지 않은 뿌리 행이다. 묶음 안 파일의 번호와 지운 보관본의 이력으로는 찾지 못한다. */
    Optional<ChatArtifact> findByIdAndVaultUserIdAndVaultBundleIdIsNullAndDeletedAtIsNull(Long id, Long vaultUserId);


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
     * 주인의 묶음 안 파일을 모두 지운다. 뿌리 행은 {@link #forgetVaulted} 가 이력으로 남긴다.
     *
     * <p>트랜잭션은 {@code ArtifactVaultService} 가 연다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from ChatArtifact a where a.vaultUserId = :userId and a.vaultBundleId = :id")
    int deleteVaultParts(@Param("id") Long id, @Param("userId") Long userId);


    /**
     * 주인의 보관본 뿌리 행을 지우기 이력으로 바꾼다. 본문과 key 번호, 경로, 크기를 비우고 지운 시각을 적는다.
     *
     * <p>지문은 남겨 자동 보관이 같은 대화·경로·본문을 다시 보관하지 못하게 한다. 지문은 해시라 본문을 되살리지 못한다.
     * 묶음 안 파일의 번호와 이미 지운 것은 바꾸지 않는다. 바꾼 줄 수가 0 이면 없거나 남의 것이다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update ChatArtifact a
               set a.vaultBody = null, a.vaultKeyId = null, a.path = '', a.byteSize = 0, a.deletedAt = :now
             where a.id = :id
               and a.vaultUserId = :userId
               and a.vaultBundleId is null
               and a.deletedAt is null
            """)
    int forgetVaulted(@Param("id") Long id, @Param("userId") Long userId, @Param("now") Instant now);


    /**
     * 대화가 이미 지워졌거나 지울 차례인 보관본의 지우기 이력을 지운다. 그 대화에서는 자동 보관이 다시 돌지 않아 이력이 필요 없다.
     *
     * <p>트랜잭션은 {@code ArtifactVaultService} 가 연다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            delete from ChatArtifact a
             where a.id = :id
               and a.vaultUserId = :userId
               and a.deletedAt is not null
               and not exists (
                   select c.id from Conversation c where c.id = a.conversationId and c.deletedAt is null)
            """)
    int deleteForgottenOfGoneConversation(@Param("id") Long id, @Param("userId") Long userId);


    /** 사용자가 지운 보관본을 그 사용자가 다시 보관할 때 이력 행을 지운다. 새 보관본이 같은 지문을 쓴다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from ChatArtifact a where a.id = :id and a.vaultUserId = :userId and a.deletedAt is not null")
    int deleteForgotten(@Param("id") Long id, @Param("userId") Long userId);


    /**
     * 그 답에 묶인 결과물 행 가운데 아직 자동 보관 대상이 아닌 것을 {@code PENDING} 으로 둔다.
     *
     * <p>같은 turn 의 완료가 두 번 와도 둘째는 0 줄이다. 파일을 지운 행은 두지 않는다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update ChatArtifact a
               set a.autoVaultState = :pending
             where a.messageId = :messageId
               and a.autoVaultState is null
               and a.vaultUserId is null
               and a.deletedAt is null
            """)
    int markAutoVaultPending(@Param("messageId") Long messageId, @Param("pending") AutoVaultState pending);


    /** 그 상태의 결과물 행 가운데 번호가 {@code afterId} 보다 큰 것을 오래된 것부터 준다. */
    List<ChatArtifact> findTop100ByAutoVaultStateAndIdGreaterThanOrderByIdAsc(AutoVaultState state, Long afterId);


    List<ChatArtifact> findByMessageIdAndAutoVaultState(Long messageId, AutoVaultState state);


    Optional<ChatArtifact> findByMessageIdAndPathAndVaultUserIdIsNull(Long messageId, String path);


    /** {@code PENDING} 인 줄을 {@code RUNNING} 으로 맡고 시도 횟수를 하나 더한다. 바꾼 줄 수가 1 이면 이 호출이 맡았다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update ChatArtifact a
               set a.autoVaultState = :running, a.autoVaultAttempts = a.autoVaultAttempts + 1
             where a.id = :id
               and a.autoVaultState = :pending
            """)
    int claimAutoVault(
        @Param("id") Long id, @Param("pending") AutoVaultState pending, @Param("running") AutoVaultState running);


    /** 상태가 {@code from} 인 줄만 {@code to} 로 바꾸고 오류 코드를 적는다. 오류 코드는 {@code FAILED} 일 때만 채운다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update ChatArtifact a
               set a.autoVaultState = :to, a.autoVaultError = :error
             where a.id = :id
               and a.autoVaultState = :from
            """)
    int moveAutoVault(
        @Param("id") Long id,
        @Param("from") AutoVaultState from,
        @Param("to") AutoVaultState to,
        @Param("error") String error);


    /** 사용자가 다시 보관을 누른 실패 줄을 처음 상태로 되돌린다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update ChatArtifact a
               set a.autoVaultState = :pending, a.autoVaultError = null, a.autoVaultAttempts = 0
             where a.id = :id
               and a.autoVaultState = :failed
            """)
    int reopenAutoVault(
        @Param("id") Long id, @Param("failed") AutoVaultState failed, @Param("pending") AutoVaultState pending);


    /** 재시작 뒤 맡은 작업이 없는 {@code RUNNING} 줄을 다시 차례에 둔다. Control Plane 은 한 인스턴스로 돈다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update ChatArtifact a set a.autoVaultState = :pending where a.autoVaultState = :running")
    int requeueRunning(@Param("running") AutoVaultState running, @Param("pending") AutoVaultState pending);
}
