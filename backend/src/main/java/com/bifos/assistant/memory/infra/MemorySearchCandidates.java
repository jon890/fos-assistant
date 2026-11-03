package com.bifos.assistant.memory.infra;

import com.bifos.assistant.memory.domain.MemorySearchCandidate;
import com.bifos.assistant.memory.domain.entity.Memory;
import com.bifos.assistant.memory.domain.type.MemoryRetrieval;
import com.bifos.assistant.memory.domain.type.MemorySensitivity;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

/**
 * {@code memory_search} 의 후보를 읽는다. 권한 조건은 {@link MemoryQueries#injectable} 을 그대로 건다.
 *
 * <p>제목과 본문은 암호문이라 질의가 직접 고르지 않는다. 엔티티를 읽어 앱에서 푼다(ADR-20261103 / memory-encryption).
 * 풀지 못한 줄은 빼고, 민감 항목의 본문은 null 로 바꿔 점수에 넘기지 않는다.
 */
@Component
@RequiredArgsConstructor
public class MemorySearchCandidates {

    private final MemoryRepository memories;


    /**
     * @param collections 에이전트가 받는 collection. null 이면 거르지 않는다
     * @param sensitiveCollections 그 가운데 민감 항목까지 받는 collection
     */
    public List<MemorySearchCandidate> candidatesFor(
        Long userId, Long groupId, Set<String> collections, Set<String> sensitiveCollections) {
        return memories.findAll(
            MemoryQueries.injectable(userId, groupId, MemoryRetrieval.SEARCH, collections, sensitiveCollections),
            Sort.by("id"))
            .stream()
            .filter(Memory::readable)
            .map(memory -> new MemorySearchCandidate(
                memory.id(),
                memory.title(),
                memory.sensitivity() == MemorySensitivity.NORMAL ? memory.content() : null,
                memory.revision(),
                memory.updatedAt()))
            .toList();
    }
}
