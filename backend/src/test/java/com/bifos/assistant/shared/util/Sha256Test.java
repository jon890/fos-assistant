package com.bifos.assistant.shared.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 성격 본문의 지문이 지켜야 할 규칙을 확인한다. */
class Sha256Test {

    @Test
    @DisplayName("null 은 빈 문자열과 같은 지문을 낸다")
    void nullGivesSameFingerprintAsEmptyString() {
        assertThat(Sha256.hex16(null)).isEqualTo(Sha256.hex16(""));
    }

    @Test
    @DisplayName("빈 문자열에도 지문이 있다")
    void emptyStringAlsoHasFingerprint() {
        assertThat(Sha256.hex16("")).isNotNull().isNotEmpty();
    }

    @Test
    @DisplayName("지문은 16진수 32글자다")
    void fingerprintIs32HexChars() {
        assertThat(Sha256.hex16("차분하게 설명하는 성격이다")).matches("[0-9a-f]{32}");
    }

    @Test
    @DisplayName("다른 본문은 다른 지문을 낸다")
    void differentBodyGivesDifferentFingerprint() {
        assertThat(Sha256.hex16("성격 하나")).isNotEqualTo(Sha256.hex16("성격 둘"));
    }
}
