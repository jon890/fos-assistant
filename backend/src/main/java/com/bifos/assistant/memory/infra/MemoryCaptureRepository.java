package com.bifos.assistant.memory.infra;

import com.bifos.assistant.memory.domain.MemoryCapture;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MemoryCaptureRepository extends JpaRepository<MemoryCapture, Long> {

    /** 한 대화에서 그 사용자가 남긴 기록을 만든 순으로 낸다. 되돌린 기록은 뺀다. */
    List<MemoryCapture> findByUserIdAndConversationIdAndUndoneAtIsNullOrderByIdAsc(Long userId, Long conversationId);

    /** 한 실행이 남긴 기록의 수다. 한 실행의 상한을 셀 때 쓴다. */
    long countByExecutionId(Long executionId);

    /** 되돌리기가 같은 기록을 두 번 처리하지 않게 잠가 읽는다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from MemoryCapture c where c.id = :id")
    Optional<MemoryCapture> findByIdForUpdate(@Param("id") Long id);
}
