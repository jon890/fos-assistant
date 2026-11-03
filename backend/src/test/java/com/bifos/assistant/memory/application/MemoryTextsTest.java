package com.bifos.assistant.memory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bifos.assistant.shared.util.Sha256;
import com.bifos.assistant.crypto.domain.OwnedTextCipher;
import com.bifos.assistant.memory.domain.entity.Memory;
import com.bifos.assistant.memory.domain.MemoryPlacement;
import com.bifos.assistant.memory.domain.type.MemoryRetrieval;
import com.bifos.assistant.memory.domain.type.MemoryScope;
import com.bifos.assistant.memory.infra.MemoryRepository;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** KEK 가 없는 설치의 기억 저장을 본다(ADR-20261103 / memory-encryption). */
class MemoryTextsTest {

    @Test
    @DisplayName("KEK 가 없으면 기억을 평문으로 넣고 암호화를 부르지 않는다")
    void storesPlainWithoutKek() {
        OwnedTextCipher cipher = mock(OwnedTextCipher.class);
        MemoryRepository repository = mock(MemoryRepository.class);
        when(cipher.enabled()).thenReturn(false);
        when(repository.saveAndFlush(any(Memory.class))).thenAnswer(invocation -> invocation.getArgument(0));
        MemoryTexts texts = new MemoryTexts(cipher, repository);
        Memory memory = Memory.accepted(
            MemoryScope.USER, 7L, null, "합성 제목", "합성 본문",
            MemoryPlacement.core(MemoryRetrieval.SEARCH), 7L, Instant.parse("2026-11-03T00:00:00Z"));

        Memory saved = texts.insert(memory);

        assertThat(saved.storedTitle()).isEqualTo("합성 제목");
        assertThat(saved.storedContent()).isEqualTo("합성 본문");
        assertThat(saved.dataKeyId()).isNull();
        assertThat(texts.seal(saved.stored(), "합성 제목", "합성 본문")).isEmpty();
        verify(cipher, never()).seal(any(), any(), any());
    }


    @Test
    @DisplayName("KEK 가 없으면 제안 중복 키와 임베딩 지문은 평문의 SHA-256 이다")
    void hashesPlainWithoutKek() {
        MemoryTexts texts = new MemoryTexts(mock(OwnedTextCipher.class), mock(MemoryRepository.class));

        assertThat(texts.proposalDedupKey(7L, "제목", "본문")).isEqualTo(Sha256.hex(7L + "\u0000제목\u0000본문"));
        assertThat(texts.embeddingFingerprint(MemoryScope.USER, 7L, "model", "제목\n본문"))
            .hasValueSatisfying(value -> assertThat(value).isEqualTo(EmbeddingText.plainFingerprint("model", "제목\n본문")));
    }
}
