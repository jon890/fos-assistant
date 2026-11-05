package com.bifos.assistant.followup.infra;

import com.bifos.assistant.followup.domain.FollowUp;
import com.bifos.assistant.followup.domain.type.FollowUpStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
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


    /** 같은 실행의 같은 결과 항목으로 이미 만든 제안이다. 키는 서버가 계산한 SHA-256 16진수다. */
    Optional<FollowUp> findByUserIdAndDedupeKey(Long userId, String dedupeKey);


    /** 그 사용자의 줄 가운데 중복 열쇠가 그 목록에 든 것이다. 옛 줄을 옮긴 제안 줄을 한 번에 찾으려고 읽는다. */
    List<FollowUp> findByUserIdAndDedupeKeyIn(Long userId, Collection<String> dedupeKeys);


    /** 그 대화에서 먼저 살펴보기가 낸 제안 줄이다(중복 열쇠가 있는 줄). 대화를 지울 때 제안의 줄과 본문을 지우려고 읽는다. */
    List<FollowUp> findByConversationIdAndDedupeKeyIsNotNull(Long conversationId);


    /**
     * 그 사용자의 그 대화에서 {@code createdAfter} 뒤에 에이전트가 낸 줄을 최근 것부터 읽는다. 결과 블록의 제안(중복 열쇠가 있는 줄)과
     * 실행이 제안한 줄(제안한 실행이 있는 줄)이다. 받아들이지 않은 채 만료한 줄({@code PROPOSED} 이고 만료 시각이 {@code now} 이전)은
     * 뺀다. 거절한 줄과 끝난 줄은 넣는다. 다음 살펴보기의 입력이 읽는다.
     */
    @Query("""
            select f from FollowUp f
             where f.userId = :userId and f.conversationId = :conversationId
               and (f.dedupeKey is not null or f.proposedByExecutionId is not null)
               and f.createdAt > :createdAfter
               and (f.status <> :proposed or f.expiresAt is null or f.expiresAt > :now)
             order by f.id desc
            """)
    List<FollowUp> findRecentSuggestions(
        @Param("userId") Long userId,
        @Param("conversationId") Long conversationId,
        @Param("createdAfter") Instant createdAfter,
        @Param("now") Instant now,
        @Param("proposed") FollowUpStatus proposed,
        Pageable page);


    /** 사용자 정리 때 그 사용자의 할 일을 모두 지운다(ADR-20261101 / user-purge). 지운 행 수를 돌려준다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from FollowUp f where f.userId = :userId")
    int deleteAllOfUser(@Param("userId") Long userId);
}
