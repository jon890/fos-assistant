package com.bifos.assistant.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.testsupport.PersistenceExtensionIntegrationTest;
import com.bifos.persistencefixture.PersistenceExtension;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.data.repository.support.Repositories;

/** 다른 기능에 저장 타입이 늘어난 실제 문맥에서도 memory의 탐색 계약을 검사한다. */
@PersistenceExtensionIntegrationTest
class MemoryPersistenceExtensionPlacementTest extends MemoryPersistencePlacementTest {
    @Autowired
    private EntityManager entityManager;

    @Autowired
    private ApplicationContext context;


    @Test
    @DisplayName("별도 문맥이 추가 엔티티와 저장소를 실제로 탐색한다")
    void discoversExtension() {
        assertThat(entityManager.getMetamodel().entity(PersistenceExtension.ExtraEntity.class).getJavaType())
            .isEqualTo(PersistenceExtension.ExtraEntity.class);
        assertThat(new Repositories(context).getRepositoryFor(PersistenceExtension.ExtraEntity.class))
            .contains(context.getBean(PersistenceExtension.ExtraRepository.class));
    }

}
