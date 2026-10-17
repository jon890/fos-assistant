package com.bifos.assistant.chat.contentdraft.infra;

import com.bifos.assistant.chat.contentdraft.domain.entity.ContentDraftJob;
import com.bifos.assistant.chat.contentdraft.domain.type.ContentDraftJobStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ContentDraftJobRepository extends JpaRepository<ContentDraftJob, Long> {
    Optional<ContentDraftJob> findBySnapshotId(Long snapshotId);


    Optional<ContentDraftJob> findByActionId(Long actionId);


    Optional<ContentDraftJob> findByJobId(String jobId);


    /** 승인 카드에 원고 요약을 붙일 때 그 줄들의 job 을 한 번에 읽는다. */
    List<ContentDraftJob> findByActionIdIn(Collection<Long> actionIds);


    /** 이 시각보다 먼저 승인했는데 아직 그 상태인 job 이다. 기한 정리가 쓴다. */
    List<ContentDraftJob> findByStatusInAndApprovedAtBefore(Collection<ContentDraftJobStatus> statuses, Instant before);


    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from ContentDraftJob j where j.id = :id")
    Optional<ContentDraftJob> lockById(@Param("id") Long id);


    /** worker 시작 허가를 한 번만 준다. 시작 응답을 잃어 UNKNOWN인 job도 같은 한 번을 쓴다. 결과 1만 허가다. */
    default int claimStart(Long jobRowId, Instant at) {
        return claimStart(jobRowId, at, ContentDraftJobStatus.SAVING,
            EnumSet.of(ContentDraftJobStatus.DISPATCHED, ContentDraftJobStatus.SAVING, ContentDraftJobStatus.UNKNOWN));
    }


    /** 의도적인 저장 클릭 허가를 한 번만 준다. 결과 1만 허가다. */
    default int claimSave(Long jobRowId, Instant at) {
        return claimSave(jobRowId, at, EnumSet.of(ContentDraftJobStatus.SAVING, ContentDraftJobStatus.UNKNOWN));
    }


    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        update ContentDraftJob j set j.startClaimedAt = :at, j.status = :saving
        where j.id = :id and j.startClaimedAt is null and j.saveClaimedAt is null and j.status in :from
        """)
    int claimStart(@Param("id") Long jobRowId, @Param("at") Instant at, @Param("saving") ContentDraftJobStatus saving,
        @Param("from") Collection<ContentDraftJobStatus> from);


    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        update ContentDraftJob j set j.saveClaimedAt = :at
        where j.id = :id and j.startClaimedAt is not null and j.saveClaimedAt is null and j.status in :from
        """)
    int claimSave(@Param("id") Long jobRowId, @Param("at") Instant at, @Param("from") Collection<ContentDraftJobStatus> from);


    List<ContentDraftJob> findByOwnerUserIdAndConversationIdOrderByIdDesc(Long ownerUserId, Long conversationId, Pageable page);


    List<ContentDraftJob> findByOwnerUserIdAndConversationIdAndIdLessThanOrderByIdDesc(Long ownerUserId, Long conversationId,
        Long beforeId, Pageable page);
}
