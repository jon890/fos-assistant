package com.bifos.assistant.attention.infra;

import com.bifos.assistant.attention.domain.AttentionEvent;
import com.bifos.assistant.attention.domain.type.AttentionEventType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AttentionEventRepository extends JpaRepository<AttentionEvent, Long> {

    /**
     * 그 사용자의 그 종류의 사건 가운데 열쇠가 {@code itemKeys} 에, 상태가 {@code stateKeys} 에 든 것이다. 이미 남긴 {@code SHOWN} 을
     * 거르는 데 쓴다. 상태까지 조건에 넣어 지난 상태의 줄을 읽지 않는다.
     */
    List<AttentionEvent> findByUserIdAndEventTypeAndItemKeyInAndStateKeyIn(
            Long userId, AttentionEventType eventType, Collection<String> itemKeys, Collection<String> stateKeys);

    /** 그 사용자의 그 항목의 그 종류의 사건 가운데 가장 나중에 넣은 것이다. */
    Optional<AttentionEvent> findFirstByUserIdAndItemKeyAndEventTypeOrderByIdDesc(
            Long userId, String itemKey, AttentionEventType eventType);

    /** 그 사용자의 사건 가운데 항목, 상태, 종류가 같은 것 중 가장 나중에 넣은 것이다. */
    Optional<AttentionEvent> findFirstByUserIdAndItemKeyAndStateKeyAndEventTypeOrderByIdDesc(
            Long userId, String itemKey, String stateKey, AttentionEventType eventType);

    /** {@code since} 이후의 모든 사용자의 사건이다. 지표가 센다. */
    List<AttentionEvent> findByCreatedAtGreaterThanEqual(Instant since);

    /**
     * {@code before} 보다 먼저 남긴 사건을 지운다.
     *
     * @return 지운 줄 수
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from AttentionEvent e where e.createdAt < :before")
    int deleteCreatedBefore(@Param("before") Instant before);
}
