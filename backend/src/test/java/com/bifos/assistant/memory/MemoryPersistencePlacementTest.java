package com.bifos.assistant.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.testsupport.BackendIntegrationTest;
import jakarta.persistence.EntityManager;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.data.repository.Repository;

/** 위치 이동 전후 JPA 이름과 스캔 대상 수가 유지되는지 실제 문맥에서 확인한다. */
@BackendIntegrationTest
class MemoryPersistencePlacementTest {
    @Autowired
    private EntityManager entityManager;

    @Autowired
    private ApplicationContext context;


    @Test
    @DisplayName("엔티티 이름과 전체 엔티티 수 및 memory 저장소 빈이 위치 이동 전과 같다")
    void preservesPersistenceDiscovery() {
        var entities = entityManager.getMetamodel().getEntities();
        assertThat(entities).hasSize(60);
        assertThat(entities.stream()
            .filter(entity -> entity.getJavaType().getPackageName().startsWith("com.bifos.assistant.memory."))
            .map(entity -> entity.getName()))
                .containsExactlyInAnyOrder("Memory", "MemoryCapture", "MemoryCollection", "MemoryRevision",
                    "ServiceToken", "ServiceTokenCollection");
        var repositories = context.getBeansOfType(Repository.class);
        assertThat(repositories).hasSize(60);
        assertThat(repositories.values().stream()
            .flatMap(bean -> Arrays.stream(bean.getClass().getInterfaces()))
            .filter(type -> type.getPackageName().equals("com.bifos.assistant.memory.infra"))
            .map(Class::getSimpleName))
                .containsExactlyInAnyOrder("MemoryRepository", "MemoryCaptureRepository", "MemoryCollectionRepository",
                    "MemoryRevisionRepository", "ServiceTokenRepository", "ServiceTokenCollectionRepository");
    }
}
