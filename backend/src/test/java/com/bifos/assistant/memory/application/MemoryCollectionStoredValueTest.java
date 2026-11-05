package com.bifos.assistant.memory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.bifos.assistant.memory.domain.MemoryCollection;
import com.bifos.assistant.user.application.GroupSettingStore;
import com.bifos.assistant.user.domain.GroupSetting;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 저장된 {@code MEMORY_COLLECTIONS} 값을 읽는 규칙을 Spring 과 DB 없이 확인한다(ADR-052). 줄을 실제로 읽는 길은 통합 검사가 본다. */
class MemoryCollectionStoredValueTest {

    private final GroupSettingStore settings = mock(GroupSettingStore.class);
    private final MemoryCollectionService service = new MemoryCollectionService(settings);


    @Test
    @DisplayName("줄이 없는 그룹은 기본 collection 일곱 개를 순서대로 받고 아무것도 저장하지 않는다")
    void groupsWithoutRowsGetSevenDefaultsWithoutSaving() {
        when(settings.read(31L, GroupSetting.MEMORY_COLLECTIONS)).thenReturn(Optional.empty());

        List<MemoryCollection> first = service.collectionsOf(31L);
        List<MemoryCollection> second = service.collectionsOf(31L);

        assertThat(first)
            .extracting(MemoryCollection::key)
            .containsExactly("core", "career", "learning", "health", "finance", "home", "identity");
        assertThat(first).extracting(MemoryCollection::displayName)
            .containsExactly("기본", "커리어", "학습", "건강", "재무", "집", "신원");
        assertThat(second).isEqualTo(first);
        // 읽기만 했고 쓰기나 잠금 읽기는 부르지 않았다
        verify(settings, times(2)).read(31L, GroupSetting.MEMORY_COLLECTIONS);
        verifyNoMoreInteractions(settings);
    }


    @Test
    @DisplayName("줄이 있는 그룹은 저장된 목록을 저장된 순서대로 받고 다른 그룹은 기본 목록이다")
    void groupWithRowGetsItsOwnListInStoredOrder() {
        when(settings.read(31L, GroupSetting.MEMORY_COLLECTIONS)).thenReturn(Optional.of("""
            {"version":1,"collections":[{"key":"home","name":"우리 집"},{"key":"core","name":"기본"}]}"""));
        when(settings.read(32L, GroupSetting.MEMORY_COLLECTIONS)).thenReturn(Optional.empty());

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
        when(settings.read(31L, GroupSetting.MEMORY_COLLECTIONS)).thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> service.collectionsOf(31L)).isInstanceOf(IllegalStateException.class);
    }
}
