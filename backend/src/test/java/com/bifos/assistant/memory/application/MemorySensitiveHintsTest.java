package com.bifos.assistant.memory.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MemorySensitiveHintsTest {
    @Test
    @DisplayName("건강, 금융, 신원과 신념의 낱말이 본문에 있으면 민감해 보인다")
    void suspectsSensitiveWords() {
        assertThat(MemorySensitiveHints.suspected("알레르기", "아들은 땅콩 알레르기가 있어")).isTrue();
        assertThat(MemorySensitiveHints.suspected("통장", "월급 통장은 국민은행이야")).isTrue();
        assertThat(MemorySensitiveHints.suspected("종교", "종교는 불교야")).isTrue();
    }

    @Test
    @DisplayName("낱말은 공백을 뺀 글에서 찾는다")
    void ignoresWhitespaceInsideWords() {
        assertThat(MemorySensitiveHints.suspected("결제", "카드 번호를 바꿨어")).isTrue();
        assertThat(MemorySensitiveHints.suspected("결제", "비밀   번호는 따로 적어 둔다")).isTrue();
    }

    @Test
    @DisplayName("제목에만 민감한 낱말이 있어도 민감해 보인다")
    void suspectsTitleOnly() {
        assertThat(MemorySensitiveHints.suspected("계좌", "국민은행에 있다")).isTrue();
    }

    @Test
    @DisplayName("메일 주소가 있으면 민감해 보인다")
    void suspectsEmailAddress() {
        assertThat(MemorySensitiveHints.suspected("연락", "메일은 user@example.com 이야"))
                .isTrue();
    }

    @Test
    @DisplayName("숫자가 여섯 개 이상 이어지면 민감해 보이고 다섯 개면 그렇지 않다")
    void suspectsLongDigitRunOnly() {
        assertThat(MemorySensitiveHints.suspected("연락", "전화는 010-1234-5678 이야")).isTrue();
        assertThat(MemorySensitiveHints.suspected("우편", "번호는 12345 이야")).isFalse();
        assertThat(MemorySensitiveHints.suspected("날짜", "1일과 2일과 3일과 4일과 5일과 6일"))
                .isFalse();
    }

    @Test
    @DisplayName("날짜 모양은 숫자열로 세지 않고 날짜가 아닌 긴 숫자열은 민감해 보인다")
    void ignoresDatesInDigitRuns() {
        assertThat(MemorySensitiveHints.suspected("생일", "생일은 1990-03-05 이야")).isFalse();
        assertThat(MemorySensitiveHints.suspected("기념일", "기념일은 2015.10.08")).isFalse();
        assertThat(MemorySensitiveHints.suspected("통장", "계좌는 110-123-456789")).isTrue();
        assertThat(MemorySensitiveHints.suspected("번호", "번호는 110-123-456789 이야"))
                .isTrue();
        assertThat(MemorySensitiveHints.suspected("번호", "번호는 1234-56-789012 이야"))
                .isTrue();
    }

    @Test
    @DisplayName("흔한 사실과 한 글자가 우연히 겹치는 낱말은 민감해 보이지 않는다")
    void doesNotSuspectOrdinaryFacts() {
        assertThat(MemorySensitiveHints.suspected("아들 이름", "아들 이름은 홍길동이야")).isFalse();
        assertThat(MemorySensitiveHints.suspected("음식", "매운 음식을 못 먹어")).isFalse();
        assertThat(MemorySensitiveHints.suspected("약속", "약속은 금요일이야")).isFalse();
        assertThat(MemorySensitiveHints.suspected("나이", "아들은 열 살이야")).isFalse();
        assertThat(MemorySensitiveHints.suspected("통화", "전화는 오후에 해")).isFalse();
    }
}
