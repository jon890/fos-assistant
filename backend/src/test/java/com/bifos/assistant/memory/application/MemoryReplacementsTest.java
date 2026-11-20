package com.bifos.assistant.memory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bifos.assistant.memory.application.dto.MemoryAccess;
import com.bifos.assistant.memory.domain.entity.Memory;
import com.bifos.assistant.memory.domain.type.MemoryEntryType;
import com.bifos.assistant.memory.domain.type.MemoryScope;
import com.bifos.assistant.memory.domain.type.MemoryStatus;
import com.bifos.assistant.memory.infra.MemoryCaptureRepository;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 대신할 수 있는 옛 기억의 판정을 본다(docs/features/memory/spec.md 의 REQ6). 저장소는 흉내 낸다. */
class MemoryReplacementsTest {

    private static final CurrentUser DAD = new CurrentUser(1L, "dad@example.com", "아빠", 10L, UserRole.ADMIN);
    private static final MemoryAccess CORE = MemoryAccess.of(Set.of("core"));

    private final MemoryRepository memories = mock(MemoryRepository.class);
    private final MemoryReplacements replacements = new MemoryReplacements(memories, mock(MemoryCaptureRepository.class));


    @Test
    @DisplayName("풀리지 않는 옛 기억과 문서와 새 기억 자신은 대신하지 않고 나머지를 번호 순으로 낸다")
    void filtersUnreadableDocumentAndSelf() {
        Memory readable = memory(3L, true, MemoryEntryType.MEMORY);
        Memory unreadable = memory(4L, false, MemoryEntryType.MEMORY);
        Memory document = memory(5L, true, MemoryEntryType.DOCUMENT);
        Memory self = memory(9L, true, MemoryEntryType.MEMORY);
        Memory earlier = memory(2L, true, MemoryEntryType.MEMORY);
        when(memories.findAllById(any())).thenReturn(List.of(readable, unreadable, document, self, earlier));

        assertThat(replacements.replaceable(DAD, CORE, List.of(3L, 4L, 5L, 9L, 2L), 9L)).containsExactly(2L, 3L);
    }


    @Test
    @DisplayName("대신할 수 있는 번호가 없으면 UPDATE 를 부르지 않는다")
    void skipsUpdateWithoutCandidates() {
        Memory unreadable = memory(4L, false, MemoryEntryType.MEMORY);
        when(memories.findAllById(any())).thenReturn(List.of(unreadable));

        assertThat(replacements.supersede(DAD, CORE, 9L, List.of(4L))).isEmpty();
        verify(memories, never()).supersedeWithin(any(), any(), any(), any());
        verify(memories, never()).supersede(any(), any(), any());
    }


    private static Memory memory(Long id, boolean readable, MemoryEntryType type) {
        Memory memory = mock(Memory.class);
        when(memory.id()).thenReturn(id);
        when(memory.scope()).thenReturn(MemoryScope.USER);
        when(memory.ownerUserId()).thenReturn(DAD.id());
        when(memory.entryType()).thenReturn(type);
        when(memory.status()).thenReturn(MemoryStatus.ACCEPTED);
        when(memory.supersededByMemoryId()).thenReturn(null);
        when(memory.readable()).thenReturn(readable);
        when(memory.collection()).thenReturn("core");
        return memory;
    }
}
