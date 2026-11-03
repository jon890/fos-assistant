package com.bifos.assistant.memory.infra;

import com.bifos.assistant.memory.domain.MemorySearchCandidate;
import com.bifos.assistant.memory.domain.entity.Memory;
import com.bifos.assistant.memory.domain.type.MemoryRetrieval;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

/**
 * {@code memory_search} 의 후보를 읽는다. 권한 조건은 {@link MemoryQueries#injectable} 을 그대로 건다.
 *
 * <p>제목과 본문은 암호문이라 질의가 직접 고르지 않는다. 엔티티를 읽어 앱에서 푼다(ADR-20261103 / memory-encryption).
 * 풀지 못한 줄은 뺀다.
 */
@Component
@RequiredArgsConstructor
public class MemorySearchCandidates {

    private final MemoryRepository memories;


    /**
     * @param collections 에이전트가 받는 collection. null 이면 거르지 않는다
     */
    public List<MemorySearchCandidate> candidatesFor(Long userId, Long groupId, Set<String> collections) {
        return memories.findAll(
            MemoryQueries.injectable(userId, groupId, MemoryRetrieval.SEARCH, collections),
            Sort.by("id"))
            .stream()
            .filter(Memory::readable)
            .map(memory -> new MemorySearchCandidate(
                memory.id(),
                memory.title(),
                memory.content(),
                memory.revision(),
                memory.updatedAt(),
                memory.scope(),
                memory.stored().ownerId()))
            .toList();
    }
}
