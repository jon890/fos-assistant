package com.bifos.assistant.memory.infra;

import com.bifos.assistant.memory.domain.ServiceTokenCollection;
import com.bifos.assistant.memory.domain.ServiceTokenCollectionId;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ServiceTokenCollectionRepository
        extends JpaRepository<ServiceTokenCollection, ServiceTokenCollectionId> {

    List<ServiceTokenCollection> findByIdTokenIdIn(Collection<Long> tokenIds);
}
