package com.bifos.assistant.attention.infra;

import com.bifos.assistant.attention.domain.AttentionControlEntry;
import com.bifos.assistant.attention.domain.type.CardKey;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AttentionControlRepository extends JpaRepository<AttentionControlEntry, Long> {

    /** 그 사용자의 모든 카드의 제어다. 판정이 억제 신호로 쓴다. */
    List<AttentionControlEntry> findByUserId(Long userId);

    Optional<AttentionControlEntry> findByUserIdAndCardKeyAndItemKey(Long userId, CardKey cardKey, String itemKey);

    /**
     * 그 사용자의 그 카드의 그 항목의 제어를 지운다.
     *
     * @return 지운 줄 수. 없었으면 0
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            delete from AttentionControlEntry c
             where c.userId = :userId and c.cardKey = :cardKey and c.itemKey = :itemKey
            """)
    int deleteByUserIdAndCardKeyAndItemKey(
            @Param("userId") Long userId, @Param("cardKey") CardKey cardKey, @Param("itemKey") String itemKey);
}
