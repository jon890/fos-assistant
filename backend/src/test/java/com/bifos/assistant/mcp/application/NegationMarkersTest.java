package com.bifos.assistant.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** {@code memory_remember} 의 바로 저장 판정이 보는 부정 표지를 고정한다(ADR-20261008 / memory-remember-guard). */
class NegationMarkersTest {

    @ParameterizedTest
    @ValueSource(strings = {"매운 음식을 못 먹는다", "먹지못해", "오이는 안 먹어", "오이 안먹어", "좋아하지 않아", "차가 없어", "교사가 아니야"})
    @DisplayName("못, 안, 않, 없, 아니 가 부정으로 쓰이면 부정 표지로 본다")
    void findsNegation(String text) {
        assertThat(NegationMarkers.present(text)).as("「%s」 는 부정이다", text).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"매운 음식을 좋아한다", "안경을 쓴다", "안녕", "안방에서 잔다", "아들 이름은 홍길동이야", "잘못 들었어"})
    @DisplayName("부정이 아닌 글과 안경, 안녕, 안방, 잘못 처럼 낱말 안의 안과 못은 부정 표지로 보지 않는다")
    void ignoresNonNegation(String text) {
        assertThat(NegationMarkers.present(text)).as("「%s」 는 부정이 아니다", text).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"오이는   안\t먹어", "오이는\n안 먹어"})
    @DisplayName("공백이 여럿이거나 탭과 줄바꿈이어도 떨어진 안을 부정으로 본다")
    void findsNegationAcrossWhitespace(String text) {
        assertThat(NegationMarkers.present(text)).as("「%s」 는 부정이다", text).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"못 먹는다"})
    @DisplayName("자모로 나뉜 글도 NFC 로 맞춰 부정을 찾는다")
    void findsNegationInDecomposedText(String text) {
        assertThat(NegationMarkers.present(text)).as("자모로 나뉜 「못 먹는다」 는 부정이다").isTrue();
    }
}
