package com.bifos.assistant.memory.infra;

import com.bifos.assistant.memory.domain.entity.ServiceTokenCollection;
import com.bifos.assistant.memory.domain.entity.ServiceTokenCollectionId;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ServiceTokenCollectionRepository
    extends
        JpaRepository<ServiceTokenCollection, ServiceTokenCollectionId> {

    List<ServiceTokenCollection> findByIdTokenIdIn(Collection<Long> tokenIds);
}
