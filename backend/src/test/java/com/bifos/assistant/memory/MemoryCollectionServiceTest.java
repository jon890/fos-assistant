package com.bifos.assistant.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.memory.application.MemoryCollectionService;
import com.bifos.assistant.memory.domain.MemoryCollection;
import com.bifos.assistant.memory.infra.MemoryCollectionRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** 그룹의 collection 목록과 기본 collection 을 확인한다(ADR-052). */
@SpringBootTest
@ActiveProfiles("test")
class MemoryCollectionServiceTest {

    @Autowired
    MemoryCollectionService service;

    @Autowired
    MemoryCollectionRepository repository;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
    }

    @Test
    @DisplayName("줄이 없는 그룹은 기본 collection 일곱 개를 순서대로 받고 다시 물어도 늘지 않는다")
    void groupsWithoutRowsGetSevenDefaultsOnce() {
        List<MemoryCollection> first = service.collectionsOf(31L);
        List<MemoryCollection> second = service.collectionsOf(31L);

        assertThat(first)
                .extracting(MemoryCollection::key)
                .containsExactly("core", "career", "learning", "health", "finance", "home", "identity");
        assertThat(first).extracting(MemoryCollection::displayName).doesNotContainNull();
        assertThat(second).extracting(MemoryCollection::key).containsExactlyElementsOf(MemoryCollection.DEFAULT_KEYS);
        assertThat(repository.count()).isEqualTo(7);
    }

    @Test
    @DisplayName("그룹마다 목록이 따로다")
    void everyGroupHasItsOwnList() {
        service.collectionsOf(31L);
        service.collectionsOf(32L);

        assertThat(repository.count()).isEqualTo(14);
        assertThat(repository.findByIdGroupIdOrderBySortOrderAsc(32L)).hasSize(7);
    }
}
