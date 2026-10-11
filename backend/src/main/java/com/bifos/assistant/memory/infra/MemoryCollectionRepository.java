package com.bifos.assistant.memory.infra;

import com.bifos.assistant.memory.domain.entity.MemoryCollection;
import com.bifos.assistant.memory.domain.entity.MemoryCollectionId;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemoryCollectionRepository extends JpaRepository<MemoryCollection, MemoryCollectionId> {

    List<MemoryCollection> findByIdGroupIdOrderBySortOrderAsc(Long groupId);
}
