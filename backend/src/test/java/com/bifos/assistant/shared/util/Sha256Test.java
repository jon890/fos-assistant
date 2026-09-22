package com.bifos.assistant.shared.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 성격 본문의 지문이 지켜야 할 규칙을 확인한다. */
class Sha256Test {

    @Test
    void null_은_빈_문자열과_같은_지문을_낸다() {
        assertThat(Sha256.hex16(null)).isEqualTo(Sha256.hex16(""));
    }

    @Test
    void 빈_문자열에도_지문이_있다() {
        assertThat(Sha256.hex16("")).isNotNull().isNotEmpty();
    }

    @Test
    void 지문은_16진수_32글자다() {
        assertThat(Sha256.hex16("차분하게 설명하는 성격이다")).matches("[0-9a-f]{32}");
    }

    @Test
    void 다른_본문은_다른_지문을_낸다() {
        assertThat(Sha256.hex16("성격 하나")).isNotEqualTo(Sha256.hex16("성격 둘"));
    }
}
