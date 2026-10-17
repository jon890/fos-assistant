package com.bifos.assistant.memory.application;

import com.bifos.assistant.memory.application.dto.MemoryLinkItem;
import com.bifos.assistant.memory.application.dto.SupersedeCandidate;
import com.bifos.assistant.memory.config.MemoryLinkProperties;
import com.bifos.assistant.memory.domain.MemoryNegation;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 새로 임베딩한 항목과 같은 주인의 비슷한 항목 가운데 값이 다른 쌍을 대체 후보로 고른다(ADR-20261015 / memory-link).
 *
 * <p>코사인 유사도가 {@code supersede-min-similarity} 이상이고, 두 글의 숫자, 날짜 낱말, 부정 표지 가운데 하나라도 다르면 후보다. 유사도가 높아도
 * 뜻이 반대일 수 있어(「땅콩 알레르기 있음」 과 「없음」) 자동으로 합치지 않고 사람에게 묻는 제안만 만든다. 나중에 고친 항목이 새 항목이다.
 */
@Component
@RequiredArgsConstructor
class SupersedeCandidates {

    private static final Pattern NUMBER = Pattern.compile("\\d+(?:[.,]\\d+)*");
    private static final Pattern DATE_WORD = Pattern.compile(
        "[월화수목금토일]요일|그제|어제|오늘|내일|모레|지난주|이번주|다음주|지난달|이번달|다음달|작년|올해|내년|아침|점심|저녁|오전|오후");

    private final MemoryLinkProperties properties;


    /**
     * {@code target} 과 견준 대체 후보를 유사도가 높은 것부터 모두 낸다. 항목당 상한은 실제로 만든 제안 수로 세야 하므로 부르는 쪽이 센다. 이미 줄이
     * 있는 쌍은 만들지 않으므로, 여기서 자르면 사람이 거절한 쌍이 상한을 채워 그다음의 새 후보가 묻히기 때문이다.
     */
    List<SupersedeCandidate> find(MemoryLinkItem target, List<MemoryLinkItem> peers) {
        return peers.stream()
            .filter(peer -> !peer.id().equals(target.id()))
            .map(peer -> candidate(target, peer, target.similarity(peer)))
            .filter(candidate -> candidate != null)
            .sorted(Comparator.comparingDouble(SupersedeCandidate::score).reversed())
            .toList();
    }


    /** 두 항목이 대체 후보인가. 자동 {@code RELATED} 가 대체 후보 쌍을 맺지 않으려고 같은 판정을 쓴다. */
    boolean isCandidate(MemoryLinkItem a, MemoryLinkItem b, double similarity) {
        return similarity >= properties.supersedeMinSimilarity() && valuesDiffer(a.text(), b.text());
    }


    private SupersedeCandidate candidate(MemoryLinkItem target, MemoryLinkItem peer, double similarity) {
        if (!isCandidate(target, peer, similarity)) {
            return null;
        }
        boolean targetNewer = target.updatedAt().isAfter(peer.updatedAt())
            || target.updatedAt().equals(peer.updatedAt()) && target.id() > peer.id();
        return targetNewer ? new SupersedeCandidate(target, peer, similarity) : new SupersedeCandidate(peer, target, similarity);
    }


    /** 두 글의 숫자 집합, 날짜 낱말 집합, 부정 표지 유무 가운데 하나라도 다른가. */
    static boolean valuesDiffer(String a, String b) {
        return !tokens(NUMBER, a, true).equals(tokens(NUMBER, b, true))
            || !tokens(DATE_WORD, a, false).equals(tokens(DATE_WORD, b, false))
            || MemoryNegation.present(a) != MemoryNegation.present(b);
    }


    private static Set<String> tokens(Pattern pattern, String text, boolean number) {
        Matcher matcher = pattern.matcher(text);
        return matcher.results()
            .map(match -> number ? match.group().replace(",", "") : match.group())
            .collect(Collectors.toSet());
    }
}
