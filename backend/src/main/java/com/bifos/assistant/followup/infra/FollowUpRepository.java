package com.bifos.assistant.followup.infra;

import com.bifos.assistant.followup.domain.FollowUp;
import com.bifos.assistant.followup.domain.type.FollowUpStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FollowUpRepository extends JpaRepository<FollowUp, Long> {

    /** 주인의 할 일이다. 남의 줄과 없는 줄을 같은 빈 값으로 숨긴다. */
    Optional<FollowUp> findByPublicIdAndUserId(UUID publicId, Long userId);

    List<FollowUp> findByUserIdAndStatusInOrderByIdAsc(Long userId, Collection<FollowUpStatus> statuses);

    /** 같은 제목의 열린 줄이다. {@code openMarker} 에 {@link FollowUp#OPEN_MARKER} 를 넘긴다. */
    Optional<FollowUp> findByUserIdAndTitleKeyAndOpenMarker(Long userId, String titleKey, Integer openMarker);
}
