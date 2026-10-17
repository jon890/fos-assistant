package com.bifos.assistant.context;

import com.bifos.assistant.memory.domain.entity.Memory;
import com.bifos.assistant.memory.domain.entity.MemoryLink;
import com.bifos.assistant.memory.domain.type.MemorySensitivity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 연결된 기억 구역을 싣는다(ADR-20261015 / memory-link). 개인 사실 구역과 질문과 관련된 기억 구역에 본문으로 실린 항목과 효력 있는 {@code RELATED} 로
 * 이어진 한 단계 이웃을 본문으로 싣는다. 두 단계 이상은 따라가지 않는다.
 *
 * <p>이웃 후보는 색인에 놓일 항목뿐이다. 그 목록은 이미 권한, 승인, 대체되지 않음 조건을 지났으므로 이 구역이 볼 수 없는 항목을 싣지 못한다. 민감 항목과
 * 암호화한 항목, 본문이 질문 구역의 항목당 상한보다 긴 항목은 싣지 않고 색인에 둔다. 남은 자리에 들지 않는 이웃도 자르지 않고 색인에 둔다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class MemoryLinkSection {

    static final String HEADER = """
            # 연결된 기억

            아래는 위에 실린 기억과 이어진 기억이다. 필요하면 번호로 memory_read 를 불러 다시 읽는다.
            """.stripTrailing();

    /** 한 실행에 싣는 이웃의 상한이다. 질문과 관련된 기억 구역보다 많이 싣지 않게 한다. */
    static final int MAX_ITEMS = 5;

    /** 사람과 에이전트가 맺은 연결(점수 없음)을 먼저, 그다음 점수가 높은 것부터, 같으면 번호 순이다. */
    private static final Comparator<Neighbor> ORDER = Comparator
        .comparing((Neighbor neighbor) -> neighbor.score() == null ? 0 : 1)
        .thenComparing(neighbor -> neighbor.score() == null ? 0 : -neighbor.score())
        .thenComparing(Neighbor::memoryId);


    /** 실은 이웃과 그 이웃을 뺀 색인이다. */
    record Placement(Set<Long> placed, MemorySearchPlacement index) {
    }


    /**
     * 이웃을 싣고 색인에서 뺀다.
     *
     * @param anchors 개인 사실 구역과 질문 구역에 본문으로 실린 항목 번호
     * @param links 그 번호를 한쪽 끝으로 둔 효력 있는 {@code RELATED}
     * @param search 질문 구역이 정한 색인. 이웃 후보는 이 색인의 항목이다
     * @param itemMaxChars 이웃 하나의 본문 글자 수 상한
     */
    static Placement place(
        Set<Long> anchors,
        List<MemoryLink> links,
        MemorySearchPlacement search,
        ContextBudgetWriter builder,
        int itemMaxChars,
        Function<Memory, ContextFreshness> freshness) {
        if (anchors.isEmpty() || links.isEmpty()) {
            return new Placement(Set.of(), search);
        }
        Map<Long, Memory> candidates = search.indexOrder().stream()
            .collect(Collectors.toMap(Memory::id, Function.identity(), (left, right) -> left));
        Set<Long> placed = new HashSet<>();
        int rank = 0;
        for (Neighbor neighbor : neighbors(anchors, links, candidates.keySet())) {
            if (placed.size() >= MAX_ITEMS) {
                break;
            }
            Memory memory = candidates.get(neighbor.memoryId());
            rank++;
            if (!plain(memory) || memory.content().length() > itemMaxChars) {
                continue;
            }
            ContextItem item = MemoryItems.item(
                memory,
                ContextSource.MEMORY_LINK,
                ContextBodyMode.INLINE,
                List.of(),
                memory.title(),
                memory.content(),
                freshness.apply(memory))
                .withSelection(new ContextSelection(ContextSelection.LINK_RELATED, rank, null, null, neighbor.score()));
            if (builder.appendIfFits(HEADER, MemoryItems.inlineLine(memory), item)) {
                placed.add(memory.id());
            }
        }
        if (placed.isEmpty()) {
            return new Placement(Set.of(), search);
        }
        List<Memory> order = search.indexOrder().stream().filter(memory -> !placed.contains(memory.id())).toList();
        Map<Long, ContextSelection> selections = new HashMap<>(search.indexSelections());
        placed.forEach(selections::remove);
        return new Placement(placed, new MemorySearchPlacement(search.placed(), order, selections));
    }


    private record Neighbor(Long memoryId, Double score) {
    }


    /**
     * 실린 항목의 이웃 가운데 색인 후보인 것을 한 번씩 낸다. 사람과 에이전트가 맺은 연결(점수 없음)을 먼저, 그다음 점수가 높은 것부터, 같으면 번호 순이다.
     * 한 이웃이 여러 실린 항목과 이어지면 가장 앞선 연결 하나로 센다.
     */
    private static List<Neighbor> neighbors(Set<Long> anchors, List<MemoryLink> links, Set<Long> candidates) {
        Map<Long, Neighbor> best = new HashMap<>();
        for (MemoryLink link : links) {
            Long other = anchors.contains(link.fromMemoryId()) ? link.toMemoryId() : link.fromMemoryId();
            if (!anchors.contains(link.fromMemoryId()) && !anchors.contains(link.toMemoryId())
                || anchors.contains(other)
                || !candidates.contains(other)) {
                continue;
            }
            Neighbor neighbor = new Neighbor(other, link.score());
            best.merge(other, neighbor, (left, right) -> ORDER.compare(left, right) <= 0 ? left : right);
        }
        List<Neighbor> ordered = new ArrayList<>(best.values());
        ordered.sort(ORDER);
        return ordered;
    }


    private static boolean plain(Memory memory) {
        return memory.sensitivity() == MemorySensitivity.NORMAL && !memory.sealed();
    }
}
