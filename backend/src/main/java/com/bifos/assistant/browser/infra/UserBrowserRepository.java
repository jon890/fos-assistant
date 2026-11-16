package com.bifos.assistant.browser.infra;

import com.bifos.assistant.browser.domain.UserBrowser;
import com.bifos.assistant.browser.domain.type.UserBrowserStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserBrowserRepository extends JpaRepository<UserBrowser, Long> {

    Optional<UserBrowser> findByUserId(Long userId);


    /** 동시 수를 셀 때 {@code STARTING} 과 {@code RUNNING} 을 넘긴다. */
    long countByStatusIn(Collection<UserBrowserStatus> statuses);


    /** 자동 중지 후보다. {@code RUNNING} 과 유휴 기준 시각을 넘긴다. */
    List<UserBrowser> findByStatusAndLastActiveAtBefore(UserBrowserStatus status, Instant before);


    List<UserBrowser> findByStatusIn(Collection<UserBrowserStatus> statuses);


    /** 끄다 실패해 컨테이너가 남았을 수 있는 줄을 센다. 그 컨테이너도 동시 수에 든다. */
    long countByStatusAndContainerIdIsNotNull(UserBrowserStatus status);


    /** 끄다 실패해 컨테이너가 남았을 수 있는 줄이다. 점검이 다시 끈다. */
    List<UserBrowser> findByStatusAndContainerIdIsNotNull(UserBrowserStatus status);


    /** 관리자 목록과 상태 맞추기가 읽는다. 사용자 하나에 한 줄이라 가족 규모에서는 작다. */
    List<UserBrowser> findAllByOrderByIdAsc();


    /** 사람이 쥔 줄이다. 점검이 화면이 닫힌 지 오래된 잠금을 푼다. */
    List<UserBrowser> findByStatusAndHumanHeldAtIsNotNull(UserBrowserStatus status);


    /**
     * 켜져 있고 아직 쥐지 않은 줄에만 사람이 쥔 시각을 적는다. 버전은 올리지 않는다. 엔티티가 이 칸을 쓰지 않아 저장과 겹쳐도 되돌아가지 않는다.
     *
     * @return 적은 줄 수. 이미 쥐었거나 켜져 있지 않으면 0
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update UserBrowser b set b.humanHeldAt = :now
            where b.userId = :userId and b.status = :running and b.humanHeldAt is null
            """)
    int holdByHuman(@Param("userId") Long userId, @Param("running") UserBrowserStatus running, @Param("now") Instant now);


    /** 사람이 쥔 것을 푼다. 풀 것이 없으면 0 이다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update UserBrowser b set b.humanHeldAt = null where b.id = :id and b.humanHeldAt is not null")
    int releaseHumanHold(@Param("id") Long id);
}
