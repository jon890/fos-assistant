package com.bifos.assistant.chat.contentdraft.infra;

import com.bifos.assistant.chat.contentdraft.domain.entity.ContentDraftJob;
import com.bifos.assistant.chat.contentdraft.domain.entity.ContentDraftSnapshot;
import com.bifos.assistant.chat.contentdraft.domain.type.ContentDraftJobStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** snapshot 줄과 그 줄의 칸인 저장 job 을 읽고 쓴다. job 이 없는 snapshot 은 job 칸이 모두 비어 있다. */
public interface ContentDraftSnapshotRepository extends JpaRepository<ContentDraftSnapshot, Long> {
    Optional<ContentDraftSnapshot> findByPublicIdAndOwnerUserIdAndConversationId(UUID publicId, Long ownerUserId, Long conversationId);


    Optional<ContentDraftSnapshot> findByPublicIdAndOwnerUserId(UUID publicId, Long ownerUserId);


    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ContentDraftSnapshot s where s.id = :id")
    Optional<ContentDraftSnapshot> lockById(@Param("id") Long id);


    Optional<ContentDraftSnapshot> findByRevisionIdAndBindingIdAndSnapshotHashAndAuthorizationFingerprintAndOwnerUserIdAndConversationId(Long revisionId, Long bindingId, String snapshotHash, String authorizationFingerprint, Long ownerUserId, Long conversationId);


    Optional<ContentDraftSnapshot> findByJobActionId(Long actionId);


    Optional<ContentDraftSnapshot> findByJobJobId(String jobId);


    /** 승인 카드에 원고 요약을 붙일 때 그 줄들의 job 을 한 번에 읽는다. */
    List<ContentDraftSnapshot> findByJobActionIdIn(Collection<Long> actionIds);


    /** 이 시각보다 먼저 승인했는데 아직 그 상태인 job 이다. 기한 정리가 쓴다. */
    List<ContentDraftSnapshot> findByJobStatusInAndJobApprovedAtBefore(Collection<ContentDraftJobStatus> statuses, Instant before);


    /**
     * 같은 사용자·대화·원고·연결에서 저장 결과가 UNKNOWN 으로 남은 다른 snapshot 의 job 이 있는지 본다.
     * 결과를 기다리는 UNKNOWN 만 센다. Control Plane 이 닫은 UNKNOWN(기한 만료·worker 가 모름·연결 변경)은 다시 조회하지 않아
     * 영영 풀리지 않으므로 세지 않는다. UNKNOWN 이면 외부 서비스에 임시저장 글이 이미 생겼을 수 있어 새 snapshot 저장을 막는 근거가 된다.
     */
    @Query("""
        select count(s) > 0 from ContentDraftSnapshot s, ContentDraftRevision r
        where s.revisionId = r.id
          and s.ownerUserId = :ownerId and s.conversationId = :conversationId
          and s.connectionId = :connectionId and r.draftId = :draftId
          and s.job.status = :unknown and (s.job.errorCode is null or s.job.errorCode not in :closed)
          and s.id <> :snapshotRowId
        """)
    boolean existsByStatusForOtherSnapshot(@Param("ownerId") Long ownerId, @Param("conversationId") Long conversationId,
        @Param("connectionId") Long connectionId, @Param("draftId") Long draftId, @Param("snapshotRowId") Long snapshotRowId,
        @Param("unknown") ContentDraftJobStatus unknown, @Param("closed") Collection<String> closed);


    default boolean existsUnknownForOtherSnapshot(Long ownerId, Long conversationId, Long connectionId, Long draftId, Long snapshotRowId) {
        return existsByStatusForOtherSnapshot(ownerId, conversationId, connectionId, draftId, snapshotRowId, ContentDraftJobStatus.UNKNOWN,
            List.of(ContentDraftJob.EXPIRED, ContentDraftJob.NOT_FOUND, ContentDraftJob.BINDING_CHANGED));
    }


    /** worker 시작 허가를 한 번만 준다. 시작 응답을 잃어 UNKNOWN인 job도 같은 한 번을 쓴다. 결과 1만 허가다. */
    default int claimStart(Long snapshotRowId, Instant at) {
        return claimStart(snapshotRowId, at, ContentDraftJobStatus.SAVING,
            EnumSet.of(ContentDraftJobStatus.DISPATCHED, ContentDraftJobStatus.SAVING, ContentDraftJobStatus.UNKNOWN));
    }


    /** 의도적인 저장 클릭 허가를 한 번만 준다. 결과 1만 허가다. */
    default int claimSave(Long snapshotRowId, Instant at) {
        return claimSave(snapshotRowId, at, EnumSet.of(ContentDraftJobStatus.SAVING, ContentDraftJobStatus.UNKNOWN));
    }


    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        update ContentDraftSnapshot s set s.job.startClaimedAt = :at, s.job.status = :saving
        where s.id = :id and s.job.startClaimedAt is null and s.job.saveClaimedAt is null and s.job.status in :from
        """)
    int claimStart(@Param("id") Long snapshotRowId, @Param("at") Instant at, @Param("saving") ContentDraftJobStatus saving,
        @Param("from") Collection<ContentDraftJobStatus> from);


    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        update ContentDraftSnapshot s set s.job.saveClaimedAt = :at
        where s.id = :id and s.job.startClaimedAt is not null and s.job.saveClaimedAt is null and s.job.status in :from
        """)
    int claimSave(@Param("id") Long snapshotRowId, @Param("at") Instant at, @Param("from") Collection<ContentDraftJobStatus> from);


    /** 이 대화에서 저장을 요청한 snapshot 이다. 저장을 늦게 요청한 것부터 읽는다. 같은 시각이면 snapshot 번호가 큰 것이 먼저다. */
    @Query("""
        select s from ContentDraftSnapshot s
        where s.ownerUserId = :ownerId and s.conversationId = :conversationId and s.job.status is not null
        order by s.job.createdAt desc, s.id desc
        """)
    List<ContentDraftSnapshot> findJobs(@Param("ownerId") Long ownerUserId, @Param("conversationId") Long conversationId, Pageable page);


    /** {@link #findJobs} 의 다음 쪽이다. cursor snapshot 보다 뒤에 오는 줄만 읽는다. */
    @Query("""
        select s from ContentDraftSnapshot s
        where s.ownerUserId = :ownerId and s.conversationId = :conversationId and s.job.status is not null
          and (s.job.createdAt < :beforeAt or (s.job.createdAt = :beforeAt and s.id < :beforeId))
        order by s.job.createdAt desc, s.id desc
        """)
    List<ContentDraftSnapshot> findJobsBefore(@Param("ownerId") Long ownerUserId, @Param("conversationId") Long conversationId,
        @Param("beforeAt") Instant beforeAt, @Param("beforeId") Long beforeId, Pageable page);
}
