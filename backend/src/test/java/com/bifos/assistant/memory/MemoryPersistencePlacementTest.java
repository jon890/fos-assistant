package com.bifos.assistant.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.memory.domain.entity.Memory;
import com.bifos.assistant.memory.domain.entity.MemoryCapture;
import com.bifos.assistant.memory.domain.entity.MemoryCollection;
import com.bifos.assistant.memory.domain.entity.MemoryCollectionId;
import com.bifos.assistant.memory.domain.entity.MemoryRevision;
import com.bifos.assistant.memory.domain.entity.MemoryRevisionId;
import com.bifos.assistant.memory.domain.entity.ServiceToken;
import com.bifos.assistant.memory.domain.entity.ServiceTokenCollection;
import com.bifos.assistant.memory.domain.entity.ServiceTokenCollectionId;
import com.bifos.assistant.memory.infra.MemoryCaptureRepository;
import com.bifos.assistant.memory.infra.MemoryCollectionRepository;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.memory.infra.MemoryRevisionRepository;
import com.bifos.assistant.memory.infra.ServiceTokenCollectionRepository;
import com.bifos.assistant.memory.infra.ServiceTokenRepository;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.support.Repositories;

/** 옮긴 소유 타입의 JPA 탐색과 저장소 연결을 실제 문맥에서 확인한다. */
@BackendIntegrationTest
class MemoryPersistencePlacementTest {
    @Autowired
    private EntityManager entityManager;

    @Autowired
    private ApplicationContext context;


    @Test
    @DisplayName("기존 memory 엔티티와 ID를 JPA가 탐색하고 각 저장소에 연결한다")
    void preservesPersistenceDiscovery() {
        Repositories repositories = new Repositories(context);
        assertPersistence(repositories, Memory.class, Long.class, MemoryRepository.class);
        assertPersistence(repositories, MemoryCapture.class, Long.class, MemoryCaptureRepository.class);
        assertPersistence(repositories, MemoryCollection.class, MemoryCollectionId.class, MemoryCollectionRepository.class);
        assertPersistence(repositories, MemoryRevision.class, MemoryRevisionId.class, MemoryRevisionRepository.class);
        assertPersistence(repositories, ServiceToken.class, Long.class, ServiceTokenRepository.class);
        assertPersistence(repositories, ServiceTokenCollection.class, ServiceTokenCollectionId.class, ServiceTokenCollectionRepository.class);
        for (Class<?> idType : new Class<?>[] {MemoryCollectionId.class, MemoryRevisionId.class, ServiceTokenCollectionId.class}) {
            assertThat(entityManager.getMetamodel().embeddable(idType).getJavaType()).isEqualTo(idType);
        }
    }


    private void assertPersistence(Repositories repositories, Class<?> entityType, Class<?> idType, Class<? extends Repository<?, ?>> repositoryType) {
        var entity = entityManager.getMetamodel().entity(entityType);
        assertThat(entity.getJavaType()).isEqualTo(entityType);
        assertThat(entity.getName()).isEqualTo(entityType.getSimpleName());
        assertThat(entity.getIdType().getJavaType()).isEqualTo(idType);
        var information = repositories.getRepositoryInformationFor(entityType).orElseThrow();
        assertThat(information.getRepositoryInterface()).isEqualTo(repositoryType);
        assertThat(information.getDomainType()).isEqualTo(entityType);
        assertThat(information.getIdType()).isEqualTo(idType);
        assertThat(repositories.getRepositoryFor(entityType)).contains(context.getBean(repositoryType));
    }
}
