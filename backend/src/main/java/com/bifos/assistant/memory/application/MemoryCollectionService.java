package com.bifos.assistant.memory.application;

import com.bifos.assistant.memory.domain.MemoryCollection;
import com.bifos.assistant.memory.infra.MemoryCollectionRepository;
import java.time.Clock;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 그룹이 쓰는 collection 의 목록을 낸다(ADR-051). */
@Service
@RequiredArgsConstructor
public class MemoryCollectionService {

    private final MemoryCollectionRepository collections;
    private final Clock clock;

    /**
     * 그룹의 collection 을 화면 순서로 낸다.
     *
     * <p>줄이 하나도 없는 그룹이면 기본 collection 을 넣고 낸다. 표를 넓힌 뒤에 생긴 그룹이 여기 해당한다. 그 전부터
     * 있던 그룹은 마이그레이션이 넣었다.
     */
    @Transactional
    public List<MemoryCollection> collectionsOf(Long groupId) {
        List<MemoryCollection> existing = collections.findByIdGroupIdOrderBySortOrderAsc(groupId);
        if (!existing.isEmpty()) {
            return existing;
        }
        return collections.saveAll(MemoryCollection.defaultsFor(groupId, clock.instant()));
    }
}
