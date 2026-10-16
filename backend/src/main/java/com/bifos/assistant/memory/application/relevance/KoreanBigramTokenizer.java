package com.bifos.assistant.memory.application.relevance;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 한국어 질문과 기억 글을 두 글자 묶음으로 쪼갠다.
 *
 * <p>NFKC 로 정규화하고 소문자로 바꾼 뒤 글자나 숫자가 아닌 글자에서 낱말을 가른다. 한 글자 낱말은 그 글자 하나를, 두 글자 이상은 겹치는
 * 두 글자 묶음을 모두 토큰으로 낸다. 형태소 분석 없이 「알레르기」 와 「알레르기가」 가 토큰 대부분을 함께 갖게 하려는 것이다.
 *
 * <p>기억 글은 {@link #documentTermCounts} 로 쪼갠다. 두 글자 이상 낱말의 글자 하나하나도 토큰으로 더해, 한 글자로 묻는 질문(「약」)이 그 글자를
 * 담은 낱말(「혈압약」)을 찾게 한다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class KoreanBigramTokenizer {

    /** 글의 토큰 집합이다. null 과 빈 글은 빈 집합이다. */
    public static Set<String> tokens(String text) {
        return new LinkedHashSet<>(termCounts(text).keySet());
    }


    /** 토큰마다 나온 횟수다. */
    public static Map<String, Integer> termCounts(String text) {
        return counts(text, false);
    }


    /** 기억 글의 토큰마다 나온 횟수다. 두 글자 이상 낱말의 글자 하나하나도 센다. BM25 의 문서 길이는 이 값의 합이다. */
    public static Map<String, Integer> documentTermCounts(String text) {
        return counts(text, true);
    }


    private static Map<String, Integer> counts(String text, boolean withCharacters) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        if (text == null || text.isEmpty()) {
            return counts;
        }
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        int[] codePoints = normalized.codePoints().toArray();
        int start = 0;
        for (int i = 0; i <= codePoints.length; i++) {
            if (i < codePoints.length && Character.isLetterOrDigit(codePoints[i])) {
                continue;
            }
            addWord(codePoints, start, i, counts, withCharacters);
            start = i + 1;
        }
        return counts;
    }


    private static void addWord(int[] codePoints, int start, int end, Map<String, Integer> counts, boolean withCharacters) {
        int length = end - start;
        if (length <= 0) {
            return;
        }
        if (length == 1) {
            counts.merge(new String(codePoints, start, 1), 1, Integer::sum);
            return;
        }
        for (int i = start; i + 1 < end; i++) {
            counts.merge(new String(codePoints, i, 2), 1, Integer::sum);
        }
        if (withCharacters) {
            for (int i = start; i < end; i++) {
                counts.merge(new String(codePoints, i, 1), 1, Integer::sum);
            }
        }
    }
}
