package com.bifos.assistant.browser.infra;

import com.bifos.assistant.browser.domain.UserBrowser;
import com.bifos.assistant.browser.domain.type.UserBrowserStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserBrowserRepository extends JpaRepository<UserBrowser, Long> {

    Optional<UserBrowser> findByUserId(Long userId);

    /** 동시 수를 셀 때 {@code STARTING} 과 {@code RUNNING} 을 넘긴다. */
    long countByStatusIn(Collection<UserBrowserStatus> statuses);

    /** 자동 중지 후보다. {@code RUNNING} 과 유휴 기준 시각을 넘긴다. */
    List<UserBrowser> findByStatusAndLastActiveAtBefore(UserBrowserStatus status, Instant before);

    List<UserBrowser> findByStatusIn(Collection<UserBrowserStatus> statuses);

    /** 관리자 목록과 상태 맞추기가 읽는다. 사용자 하나에 한 줄이라 가족 규모에서는 작다. */
    List<UserBrowser> findAllByOrderByIdAsc();
}
