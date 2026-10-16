package com.bifos.assistant.memory.relevance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.memory.application.relevance.KeywordScore;
import com.bifos.assistant.memory.application.relevance.KeywordScorer;
import com.bifos.assistant.memory.application.relevance.KoreanBigramTokenizer;
import com.bifos.assistant.memory.application.relevance.MemoryRelevanceProperties;
import com.bifos.assistant.memory.application.relevance.RelevanceCandidate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KeywordScorerTest {

    private final KeywordScorer scorer = new KeywordScorer();


    @Test
    @DisplayName("한 글자 낱말은 그 글자 하나를, 긴 낱말은 겹치는 두 글자 묶음을 토큰으로 낸다")
    void splitsWordsIntoBigrams() {
        assertThat(KoreanBigramTokenizer.tokens("우리 딸 알레르기"))
            .containsExactly("우리", "딸", "알레", "레르", "르기");
    }


    @Test
    @DisplayName("전각 글자와 대문자는 NFKC 와 소문자로 같은 토큰이 된다")
    void normalizesWidthAndCase() {
        assertThat(KoreanBigramTokenizer.tokens("ＡＢＣ")).isEqualTo(KoreanBigramTokenizer.tokens("abc"));
        assertThat(KoreanBigramTokenizer.tokens("ABC")).containsExactly("ab", "bc");
    }


    @Test
    @DisplayName("퍼센트와 밑줄과 역슬래시만 있는 글과 빈 글은 토큰이 없다")
    void returnsNoTokensForSeparatorsOnly() {
        assertThat(KoreanBigramTokenizer.tokens("%_\\")).isEmpty();
        assertThat(KoreanBigramTokenizer.tokens("")).isEmpty();
        assertThat(KoreanBigramTokenizer.tokens(null)).isEmpty();
    }


    @Test
    @DisplayName("본문에 질문 낱말이 있는 항목이 제목만 겹치는 항목보다 위에 오고, 본문에만 낱말이 있어도 점수를 받는다")
    void ranksBodyMatchesAboveTitleOnlyMatches() {
        List<RelevanceCandidate> candidates = List.of(
            new RelevanceCandidate(1L, "알레르기 약", "학원은 화요일"),
            new RelevanceCandidate(2L, "딸 건강", "딸은 땅콩 알레르기가 있다"),
            new RelevanceCandidate(3L, "장보기", "우유와 계란"),
            new RelevanceCandidate(4L, "음식", "땅콩 알레르기 조심"));

        List<KeywordScore> scores = scorer.score("딸 알레르기 뭐였지", candidates, 0.3);

        assertThat(scores).extracting(KeywordScore::memoryId).startsWith(2L).contains(1L, 4L).doesNotContain(3L);
        assertThat(indexOf(scores, 2L)).isLessThan(indexOf(scores, 1L));
    }


    @Test
    @DisplayName("한 글자로 묻는 질문은 그 글자를 담은 낱말을 찾는다")
    void findsSingleCharacterQueryInsideLongerWords() {
        List<RelevanceCandidate> candidates = List.of(
            new RelevanceCandidate(1L, "혈압약 복용 시간", null),
            new RelevanceCandidate(2L, "장보기", "우유와 계란"));

        assertThat(scorer.score("약", candidates, 0.3)).extracting(KeywordScore::memoryId).containsExactly(1L);
        assertThat(KoreanBigramTokenizer.documentTermCounts("혈압약")).containsKeys("혈압", "압약", "혈", "압", "약");
        assertThat(KoreanBigramTokenizer.tokens("혈압약")).containsExactly("혈압", "압약");
    }


    @Test
    @DisplayName("공통 토큰이 coverage 아래인 항목은 빠진다")
    void dropsCandidatesBelowCoverage() {
        List<RelevanceCandidate> candidates = List.of(
            new RelevanceCandidate(1L, "딸", null),
            new RelevanceCandidate(2L, "딸 알레르기 뭐였지", null));

        List<KeywordScore> scores = scorer.score("딸 알레르기 뭐였지", candidates, 0.3);

        assertThat(scores).extracting(KeywordScore::memoryId).containsExactly(2L);
    }


    @Test
    @DisplayName("본문이 null 인 항목은 본문의 낱말로 점수를 받지 못한다")
    void ignoresMissingBody() {
        List<RelevanceCandidate> candidates = List.of(new RelevanceCandidate(1L, "건강", null));

        assertThat(scorer.score("땅콩 알레르기", candidates, 0.3)).isEmpty();
    }


    @Test
    @DisplayName("점수가 같으면 번호 오름차순이고, 빈 질문은 빈 목록이다")
    void breaksTiesByIdAndReturnsEmptyForBlankQuestion() {
        List<RelevanceCandidate> candidates = List.of(
            new RelevanceCandidate(9L, "땅콩", null),
            new RelevanceCandidate(3L, "땅콩", null));

        assertThat(scorer.score("땅콩", candidates, 0.3)).extracting(KeywordScore::memoryId).containsExactly(3L, 9L);
        assertThat(scorer.score("  ", candidates, 0.3)).isEmpty();
        assertThat(scorer.score(null, candidates, 0.3)).isEmpty();
    }


    @Test
    @DisplayName("관련 판정 설정은 비어 있으면 기본값이고 범위 밖 값은 거절한다")
    void validatesRelevanceProperties() {
        MemoryRelevanceProperties defaults = MemoryRelevanceProperties.defaults();
        assertThat(defaults.keywordMinCoverage()).isEqualTo(0.3);
        assertThat(defaults.semanticMinSimilarity()).isEqualTo(0.5);
        assertThat(defaults.rrfK()).isEqualTo(60);

        assertThatThrownBy(() -> new MemoryRelevanceProperties(1.5, null, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MemoryRelevanceProperties(null, -0.1, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MemoryRelevanceProperties(null, null, 0))
            .isInstanceOf(IllegalArgumentException.class);
    }


    private static int indexOf(List<KeywordScore> scores, Long memoryId) {
        for (int i = 0; i < scores.size(); i++) {
            if (scores.get(i).memoryId().equals(memoryId)) {
                return i;
            }
        }
        return -1;
    }
}
