package com.bifos.assistant.shared.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 성격 본문의 지문이 지켜야 할 규칙을 확인한다. */
class Sha256Test {

    @Test
    @DisplayName("표준 벡터의 전체 해시와 앞 16바이트를 보존한다")
    void preservesKnownVectorsAndPrefix() {
        assertThat(Sha256.hex(null)).isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        assertThat(Sha256.hex("abc")).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(Sha256.hex16("abc")).isEqualTo("ba7816bf8f01cfea414140de5dae2223");
    }

    @Test
    @DisplayName("분할 update의 해시가 문자열 해시와 같고 호출별 digest가 독립적이다")
    void streamsChunksWithIndependentInstances() {
        var first = Sha256.newDigest();
        var second = Sha256.newDigest();
        assertThat(first).isNotSameAs(second);
        first.update("a".getBytes(StandardCharsets.UTF_8));
        second.update("abc".getBytes(StandardCharsets.UTF_8));
        first.update("bc".getBytes(StandardCharsets.UTF_8));
        assertThat(HexFormat.of().formatHex(first.digest())).isEqualTo(Sha256.hex("abc"));
        assertThat(HexFormat.of().formatHex(second.digest())).isEqualTo(Sha256.hex("abc"));
        assertThat(HexFormat.of().formatHex(Sha256.newDigest().digest())).isEqualTo(Sha256.hex(""));
    }

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
