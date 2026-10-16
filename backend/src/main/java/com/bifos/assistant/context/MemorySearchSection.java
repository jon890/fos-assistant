package com.bifos.assistant.context;

import com.bifos.assistant.memory.application.relevance.MemoryRelevance;
import com.bifos.assistant.memory.application.relevance.QueryEmbedder;
import com.bifos.assistant.memory.application.relevance.RelevanceCandidate;
import com.bifos.assistant.memory.application.relevance.RelevanceResult;
import com.bifos.assistant.memory.application.relevance.RelevanceScore;
import com.bifos.assistant.memory.domain.entity.Memory;
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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 질문과 관련된 기억 구역을 싣고 색인에 놓을 순서를 정한다(ADR-20261015 / memory-hybrid-selection).
 *
 * <p>후보는 개인 사실 구역에 실리지 않은 색인 후보다. 관련 있는 항목을 점수 순으로 보며, 일반 본문이 {@code itemMaxChars} 이하이고 구역 합계가
 * {@code maxChars} 와 남은 자리 안에 드는 것을 {@code maxItems} 건까지 싣는다. 들어가지 않는 항목은 자르지 않고 건너뛰어 색인에 남긴다.
 *
 * <p>민감 항목과 암호화한 항목은 본문을 점수에 넘기지 않는다. 제목으로만 점수를 받고 이 구역에 싣지 않는다.
 */
@Component
@RequiredArgsConstructor
public class MemorySearchSection {

    static final String HEADER = """
            # 질문과 관련된 기억

            아래는 지금 질문과 관련 있어 보이는 기억이다. 필요하면 번호로 memory_read 를 불러 다시 읽는다.
            """.stripTrailing();

    private final MemoryRelevance relevance;
    private final MemorySearchSectionProperties properties;
    private final QueryEmbedder queryEmbedder;


    /**
     * 구역을 싣고 색인에 놓을 항목과 그 순서를 낸다.
     *
     * @param question 질문. null 이거나 공백뿐이면 구역을 싣지 않고 색인은 번호 순이다
     * @param candidates 개인 사실 구역에 실리지 않은 색인 후보
     * @param indexOnly 점수는 받되 구역에 싣지 않고 색인에 둘 항목 번호
     * @param freshness 항목의 신선도
     */
    MemorySearchPlacement place(
        String question,
        List<Memory> candidates,
        Set<Long> indexOnly,
        ContextBudgetWriter builder,
        Function<Memory, ContextFreshness> freshness) {
        if (question == null || question.isBlank() || candidates.isEmpty() || properties.maxItems() == 0) {
            return MemorySearchPlacement.byId(candidates);
        }
        String stripped = question.strip();
        RelevanceResult result = relevance.score(
            stripped, queryEmbedder.embed(stripped), candidates.stream().map(MemorySearchSection::candidateOf).toList());
        if (result.ranked().isEmpty()) {
            return MemorySearchPlacement.byId(candidates);
        }
        Map<Long, Memory> byId = candidates.stream().collect(Collectors.toMap(Memory::id, Function.identity()));
        Set<Long> placed = new HashSet<>();
        long used = 0;
        for (int i = 0; i < result.ranked().size() && placed.size() < properties.maxItems(); i++) {
            RelevanceScore score = result.ranked().get(i);
            Memory memory = byId.get(score.memoryId());
            if (indexOnly.contains(memory.id()) || !plain(memory) || memory.content().length() > properties.itemMaxChars()) {
                continue;
            }
            String line = MemoryItems.inlineLine(memory);
            long cost = ContextBudgetWriter.next(builder.isEmpty(), !placed.isEmpty(), HEADER, line).length();
            if (used + cost > properties.maxChars()) {
                continue;
            }
            ContextItem item = MemoryItems.item(
                memory,
                ContextSource.MEMORY_SEARCH,
                ContextBodyMode.INLINE,
                List.of(),
                memory.title(),
                memory.content(),
                freshness.apply(memory))
                .withSelection(selectionOf(result.selector(), i + 1, score));
            if (builder.appendIfFits(HEADER, line, item)) {
                placed.add(memory.id());
                used += cost;
            }
        }
        return indexAfter(result, candidates, byId, placed);
    }


    /** 점수가 있는 항목을 점수 순으로, 나머지를 번호 순으로 늘어놓는다. 점수가 있는 항목은 색인 안의 차례를 순위로 받는다. */
    private static MemorySearchPlacement indexAfter(RelevanceResult result, List<Memory> candidates, Map<Long, Memory> byId, Set<Long> placed) {
        String indexSelector = ContextSelection.indexSelectorOf(result.selector());
        List<Memory> order = new ArrayList<>();
        Map<Long, ContextSelection> selections = new HashMap<>();
        for (RelevanceScore score : result.ranked()) {
            if (placed.contains(score.memoryId())) {
                continue;
            }
            order.add(byId.get(score.memoryId()));
            selections.put(score.memoryId(), selectionOf(indexSelector, order.size(), score));
        }
        candidates.stream()
            .filter(memory -> !placed.contains(memory.id()) && !selections.containsKey(memory.id()))
            .sorted(Comparator.comparing(Memory::id))
            .forEach(order::add);
        return new MemorySearchPlacement(placed, order, selections);
    }


    private static ContextSelection selectionOf(String selector, int rank, RelevanceScore score) {
        return new ContextSelection(selector, rank, score.keyword(), score.semantic(), score.fused());
    }


    /** 본문을 풀지 않는다. 일반 항목만 본문을 점수에 넘긴다. */
    private static RelevanceCandidate candidateOf(Memory memory) {
        return new RelevanceCandidate(memory.id(), memory.title(), plain(memory) ? memory.content() : null);
    }


    private static boolean plain(Memory memory) {
        return memory.sensitivity() == MemorySensitivity.NORMAL && !memory.sealed();
    }
}
