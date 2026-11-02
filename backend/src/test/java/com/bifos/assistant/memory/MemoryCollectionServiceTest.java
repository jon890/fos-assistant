package com.bifos.assistant.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.memory.application.MemoryCollectionService;
import com.bifos.assistant.memory.domain.MemoryCollection;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** 그룹의 collection 목록을 {@code group_setting} 의 {@code MEMORY_COLLECTIONS} 줄에서 읽는지 확인한다(ADR-052). */
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
    @DisplayName("줄이 없는 그룹은 기본 collection 일곱 개를 순서대로 받고 아무것도 저장하지 않는다")
    void groupsWithoutRowsGetSevenDefaultsWithoutSaving() {
        List<MemoryCollection> first = service.collectionsOf(31L);
        List<MemoryCollection> second = service.collectionsOf(31L);

        assertThat(first)
            .extracting(MemoryCollection::key)
            .containsExactly("core", "career", "learning", "health", "finance", "home", "identity");
        assertThat(first).extracting(MemoryCollection::displayName)
            .containsExactly("기본", "커리어", "학습", "건강", "재무", "집", "신원");
        assertThat(second).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM group_setting WHERE setting_key = 'MEMORY_COLLECTIONS'", Long.class))
            .isZero();
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


    @ParameterizedTest
    @ValueSource(strings = {
        "not json",
        "null",
        "{}",
        """
            {"version":2,"collections":[{"key":"core","name":"기본"}]}""",
        """
            {"version":1}""",
        """
            {"version":1,"collections":[]}""",
        """
            {"version":1,"collections":[{"key":"core","name":"기본"},{"key":"core","name":"또 기본"}]}""",
        """
            {"version":1,"collections":[{"key":"core"}]}""",
        """
            {"version":1,"collections":[{"key":" ","name":"기본"}]}""",
        """
            {"version":1,"collections":[null]}"""})
    @DisplayName("모르는 버전이거나 형식이 깨진 값은 기본 목록으로 대신하지 않고 오류로 올린다")
    void brokenStoredValueFailsInsteadOfFallingBackToDefaults(String stored) {
        jdbc.update("INSERT INTO group_setting (group_id, setting_key, setting_value) VALUES (?, 'MEMORY_COLLECTIONS', ?)",
            31L, stored);

        assertThatThrownBy(() -> service.collectionsOf(31L)).isInstanceOf(IllegalStateException.class);
    }
}
