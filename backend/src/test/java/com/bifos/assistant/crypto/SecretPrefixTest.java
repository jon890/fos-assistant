package com.bifos.assistant.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.crypto.domain.SecretPrefix;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SecretPrefixTest {

    @Test
    @DisplayName("16자 이상일 때만 앞 4자를 남기고 서로게이트 쌍을 가르지 않는다")
    void keepsFourCharactersOnlyForLongValues() {
        assertThat(SecretPrefix.of(null)).isNull();
        assertThat(SecretPrefix.of("a".repeat(15))).isNull();
        assertThat(SecretPrefix.of("abcd" + "x".repeat(12))).isEqualTo("abcd");
        assertThat(SecretPrefix.of("abc😀" + "x".repeat(12))).isEqualTo("abc");
    }
}
