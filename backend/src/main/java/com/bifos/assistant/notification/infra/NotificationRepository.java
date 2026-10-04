package com.bifos.assistant.notification.infra;

import com.bifos.assistant.notification.domain.Notification;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /** 그 사용자의 알림 한 줄이다. 남의 줄은 없는 줄과 같다. */
    Optional<Notification> findByPublicIdAndUserId(UUID publicId, Long userId);

    /** 그 사용자의 알림을 최근 것부터 첫 쪽만큼 읽는다. 같은 시각이면 번호가 큰 쪽이 앞이다. 개수는 {@code pageable} 이 정한다. */
    List<Notification> findByUserIdOrderByCreatedAtDescIdDesc(Long userId, Pageable pageable);

    /** {@code (createdAt, id)} 로 정한 자리 바로 다음 줄부터 읽는다. 정렬은 첫 쪽과 같다. */
    @Query("""
            select n from Notification n
             where n.userId = :userId
               and (n.createdAt < :createdAt or (n.createdAt = :createdAt and n.id < :id))
             order by n.createdAt desc, n.id desc
            """)
    List<Notification> findPageAfter(
            @Param("userId") Long userId,
            @Param("createdAt") Instant createdAt,
            @Param("id") Long id,
            Pageable pageable);

    long countByUserIdAndReadAtIsNull(Long userId);

    /**
     * 그 사용자의 읽지 않은 줄을 모두 읽음으로 바꾼다. 이미 읽은 줄의 시각은 그대로 둔다.
     *
     * @return 바꾼 줄 수
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Notification n set n.readAt = :now where n.userId = :userId and n.readAt is null")
    int markAllRead(@Param("userId") Long userId, @Param("now") Instant now);

    /**
     * {@code before} 보다 먼저 만든 줄을 읽었는지와 상관없이 지운다.
     *
     * @return 지운 줄 수
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from Notification n where n.createdAt < :before")
    int deleteByCreatedAtBefore(@Param("before") Instant before);
}
