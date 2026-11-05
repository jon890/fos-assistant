package com.bifos.assistant.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.memory.application.MemoryCollectionService;
import com.bifos.assistant.memory.domain.MemoryCollection;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 그룹의 collection 목록을 {@code group_setting} 의 {@code MEMORY_COLLECTIONS} 줄에서 실제로 읽는지 확인한다(ADR-052).
 * 기본 목록과 깨진 값의 해석은 {@code MemoryCollectionStoredValueTest} 가 본다.
 */
@BackendIntegrationTest
class MemoryCollectionServiceTest {

    @Autowired
    MemoryCollectionService service;

    @Autowired
    JdbcTemplate jdbc;


    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM group_setting WHERE setting_key = 'MEMORY_COLLECTIONS'");
    }


    @Test
    @DisplayName("줄이 있는 그룹은 저장된 목록을 저장된 순서대로 받고 다른 그룹은 기본 목록이다")
    void groupWithRowGetsItsOwnListInStoredOrder() {
        jdbc.update("INSERT INTO group_setting (group_id, setting_key, setting_value) VALUES (?, 'MEMORY_COLLECTIONS', ?)",
            31L, """
                {"version":1,"collections":[{"key":"home","name":"우리 집"},{"key":"core","name":"기본"}]}""");

        assertThat(service.collectionsOf(31L))
            .containsExactly(new MemoryCollection("home", "우리 집"), new MemoryCollection("core", "기본"));
        assertThat(service.collectionsOf(32L)).isEqualTo(MemoryCollection.DEFAULTS);
    }
}
