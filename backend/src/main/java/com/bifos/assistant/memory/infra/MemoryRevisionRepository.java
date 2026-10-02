package com.bifos.assistant.memory.infra;

import com.bifos.assistant.memory.domain.MemoryRevision;
import com.bifos.assistant.memory.domain.MemoryRevisionId;
import com.bifos.assistant.memory.domain.type.MemorySensitivity;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MemoryRevisionRepository extends JpaRepository<MemoryRevision, MemoryRevisionId> {

    /** 한 항목의 물러난 판을 오래된 것부터 낸다. 지운 항목의 번호로도 찾는다. */
    List<MemoryRevision> findByIdMemoryIdOrderByIdRevisionAsc(Long memoryId);

    /** 암호화되지 않은 채 남은 판을 찾는다. 민감도로 좁혀 부른다(ADR-055). */
    List<MemoryRevision> findBySensitivityAndContentKeyIdIsNull(MemorySensitivity sensitivity);

    /** 한 항목의 판 가운데 평문으로 남은 것을 찾는다. 그 항목을 민감으로 바꿀 때 함께 암호화한다(ADR-055). */
    List<MemoryRevision> findByIdMemoryIdAndContentKeyIdIsNull(Long memoryId);

    /** 판 한 줄을 쓰기 잠금으로 읽는다. 기동할 때의 보정이 저장 모양을 바꾸기 전에 쓴다(ADR-055). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from MemoryRevision r where r.id = :id")
    Optional<MemoryRevision> findByIdForUpdate(@Param("id") MemoryRevisionId id);
}
