package com.bifos.assistant.followup.infra;

import com.bifos.assistant.followup.domain.FollowUp;
import com.bifos.assistant.followup.domain.type.FollowUpStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FollowUpRepository extends JpaRepository<FollowUp, Long> {

    /** 주인의 할 일이다. 남의 줄과 없는 줄을 같은 빈 값으로 숨긴다. */
    Optional<FollowUp> findByPublicIdAndUserId(UUID publicId, Long userId);

    /**
     * 주인의 할 일을 상태나 칸을 바꾸기 전에 잠그고 읽는다. 남의 줄과 없는 줄을 같은 빈 값으로 숨긴다.
     *
     * <p>같은 줄의 전이와 고치기가 한 번에 하나씩 돈다. 뒤에 온 쪽은 앞선 쪽이 커밋한 상태를 읽고 판정하므로 앞선 쪽의 변경을 덮지 않는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from FollowUp f where f.publicId = :publicId and f.userId = :userId")
    Optional<FollowUp> findByPublicIdAndUserIdForUpdate(@Param("publicId") UUID publicId, @Param("userId") Long userId);

    List<FollowUp> findByUserIdAndStatusInOrderByIdAsc(Long userId, Collection<FollowUpStatus> statuses);

    /** 같은 제목의 열린 줄이다. {@code openMarker} 에 {@link FollowUp#OPEN_MARKER} 를 넘긴다. */
    Optional<FollowUp> findByUserIdAndTitleKeyAndOpenMarker(Long userId, String titleKey, Integer openMarker);

    /** 그 대화에서 {@code closedAfter} 뒤에 그 상태로 끝난 같은 제목의 줄이 있는가. 거절한 제안을 다시 받지 않으려고 본다. */
    boolean existsByConversationIdAndTitleKeyAndStatusAndClosedAtAfter(
            Long conversationId, String titleKey, FollowUpStatus status, Instant closedAfter);

    long countByConversationIdAndStatus(Long conversationId, FollowUpStatus status);

    /** 점검 대화에서 제안 상한을 셀 때 최근 제안만 포함한다. */
    long countByConversationIdAndStatusAndCreatedAtGreaterThanEqual(
            Long conversationId, FollowUpStatus status, Instant createdAfter);

    /** 그 실행이 제안한 줄의 수다. 끝난 줄도 센다. */
    long countByProposedByExecutionId(Long executionId);
}
