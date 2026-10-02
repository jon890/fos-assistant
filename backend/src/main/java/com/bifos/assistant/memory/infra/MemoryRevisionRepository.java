package com.bifos.assistant.memory.infra;

import com.bifos.assistant.memory.domain.MemoryRevision;
import com.bifos.assistant.memory.domain.MemoryRevisionId;
import com.bifos.assistant.memory.domain.type.MemorySensitivity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemoryRevisionRepository extends JpaRepository<MemoryRevision, MemoryRevisionId> {

    /** 한 항목의 물러난 판을 오래된 것부터 낸다. 지운 항목의 번호로도 찾는다. */
    List<MemoryRevision> findByIdMemoryIdOrderByIdRevisionAsc(Long memoryId);

    /** 암호화되지 않은 채 남은 판을 찾는다. 민감도로 좁혀 부른다(ADR-055). */
    List<MemoryRevision> findBySensitivityAndContentKeyIdIsNull(MemorySensitivity sensitivity);
}
