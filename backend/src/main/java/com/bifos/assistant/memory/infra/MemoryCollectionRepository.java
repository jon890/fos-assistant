package com.bifos.assistant.memory.infra;

import com.bifos.assistant.memory.domain.MemoryCollection;
import com.bifos.assistant.memory.domain.MemoryCollectionId;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemoryCollectionRepository extends JpaRepository<MemoryCollection, MemoryCollectionId> {

    List<MemoryCollection> findByIdGroupIdOrderBySortOrderAsc(Long groupId);
}
