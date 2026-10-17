package com.bifos.assistant.memory.application;

import com.bifos.assistant.memory.application.dto.MemoryLinkItem;
import com.bifos.assistant.memory.application.dto.RelatedPair;
import com.bifos.assistant.memory.config.MemoryLinkProperties;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 새로 임베딩한 항목과 서로의 상위 k 안에 드는 같은 범위의 항목을 자동 {@code RELATED} 쌍으로 고른다(ADR-20261015 / memory-link).
 *
 * <p>한쪽만 상위이면 맺지 않는다. 비슷한 글이 많은 항목에 연결이 몰리지 않게 하려는 것이다. 유사도가 {@code related-min-similarity} 아래이거나 대체
 * 후보인 쌍은 맺지 않는다. 항목마다의 상한은 링크를 만드는 쪽이 지금 수를 세어 지킨다.
 */
@Component
@RequiredArgsConstructor
class RelatedLinker {

    private final MemoryLinkProperties properties;
    private final SupersedeCandidates supersedes;


    /**
     * {@code target} 과 서로 상위 k 안에 드는 항목을 유사도가 높은 것부터 낸다. {@code peers} 는 target 과 같은 범위의 항목이다.
     *
     * <p>target 의 상위 k 와 그 k 개 각각의 상위 k 만 계산한다. 항목 수가 n 이면 (k+1)n 번 견준다.
     */
    List<RelatedPair> find(MemoryLinkItem target, List<MemoryLinkItem> peers) {
        List<MemoryLinkItem> all = Stream.concat(Stream.of(target), peers.stream())
            .collect(Collectors.toMap(MemoryLinkItem::id, Function.identity(), (left, right) -> left))
            .values()
            .stream()
            .toList();
        return topK(target, all).stream()
            .filter(peer -> topK(peer, all).stream().anyMatch(item -> item.id().equals(target.id())))
            .map(peer -> new RelatedPair(target, peer, target.similarity(peer)))
            .sorted(Comparator.comparingDouble(RelatedPair::score).reversed())
            .toList();
    }


    /** 한 항목과 가장 비슷한 k 개 가운데 아래 값을 넘고 대체 후보가 아닌 항목이다. */
    private List<MemoryLinkItem> topK(MemoryLinkItem item, List<MemoryLinkItem> all) {
        return all.stream()
            .filter(other -> !other.id().equals(item.id()))
            .map(other -> Map.entry(other, item.similarity(other)))
            .filter(entry -> entry.getValue() >= properties.relatedMinSimilarity())
            .filter(entry -> !supersedes.isCandidate(item, entry.getKey(), entry.getValue()))
            .sorted(Map.Entry.<MemoryLinkItem, Double>comparingByValue().reversed())
            .limit(properties.relatedTopK())
            .map(Map.Entry::getKey)
            .toList();
    }
}
