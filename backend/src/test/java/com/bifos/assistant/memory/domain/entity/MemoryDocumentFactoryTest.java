package com.bifos.assistant.memory.domain.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.memory.domain.type.MemoryEntryType;
import com.bifos.assistant.memory.domain.type.MemoryRetrieval;
import com.bifos.assistant.memory.domain.type.MemoryScope;
import com.bifos.assistant.memory.domain.type.MemoryStatus;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 문서를 만드는 팩토리가 정하는 값을 Spring 과 DB 없이 확인한다(ADR-057). */
class MemoryDocumentFactoryTest {

    private static final Instant NOW = Instant.parse("2026-10-02T00:00:00Z");


    @Test
    @DisplayName("문서는 개인 범위의 승인된 항목이고 꺼내는 방식은 늘 SEARCH 다")
    void documentIsAcceptedUserItemRetrievedBySearch() {
        Memory memory = Memory.document(1L, "identity", "application-profile", "제목", "본문", NOW);

        assertThat(memory.entryType()).isEqualTo(MemoryEntryType.DOCUMENT);
        assertThat(memory.status()).isEqualTo(MemoryStatus.ACCEPTED);
        assertThat(memory.scope()).isEqualTo(MemoryScope.USER);
        assertThat(memory.retrieval()).isEqualTo(MemoryRetrieval.SEARCH);
        assertThat(memory.collection()).isEqualTo("identity");
        assertThat(memory.groupId()).isNull();
    }

}
