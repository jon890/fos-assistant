package com.bifos.assistant.context.application.dto;

import com.bifos.assistant.memory.domain.entity.Memory;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 질문과 관련된 기억 구역을 실은 결과다.
 *
 * @param placed 구역에 실은 항목 번호
 * @param indexOrder 색인에 놓을 항목. 이 차례로 싣는다
 * @param indexSelections 점수가 있는 색인 항목의 선택 근거. 점수가 없는 항목은 맵에 없다
 */
public record MemorySearchPlacement(Set<Long> placed, List<Memory> indexOrder, Map<Long, ContextSelection> indexSelections) {

    /** 구역을 싣지 않고 색인을 번호 순으로 둔다. */
    public static MemorySearchPlacement byId(List<Memory> candidates) {
        return new MemorySearchPlacement(
            Set.of(), candidates.stream().sorted(Comparator.comparing(Memory::id)).toList(), Map.of());
    }
}
