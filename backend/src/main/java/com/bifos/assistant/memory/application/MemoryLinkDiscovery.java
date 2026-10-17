package com.bifos.assistant.memory.application;

import com.bifos.assistant.memory.application.dto.MemoryLinkItem;
import com.bifos.assistant.memory.application.dto.RelatedPair;
import com.bifos.assistant.memory.application.dto.SupersedeCandidate;
import com.bifos.assistant.memory.config.MemoryLinkProperties;
import com.bifos.assistant.memory.domain.entity.Memory;
import com.bifos.assistant.memory.domain.entity.MemoryEmbedding;
import com.bifos.assistant.memory.domain.type.MemoryScope;
import com.bifos.assistant.memory.domain.type.MemoryStatus;
import com.bifos.assistant.memory.infra.MemoryEmbeddingRepository;
import com.bifos.assistant.memory.infra.MemoryRepository;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 새로 임베딩한 항목마다 같은 주인의 항목과 견주어 대체 제안과 자동 {@code RELATED} 를 만든다(ADR-20261015 / memory-link).
 *
 * <p>임베딩을 채우는 작업이 쓰기 트랜잭션을 마친 뒤 부른다. 그 작업은 임베딩이 켜져 있을 때만 돌므로 꺼져 있으면 이 클래스도 불리지 않는다. 대체는 늘 제안이라 사람이 받아들이기 전에는 주입이
 * 바뀌지 않는다. 한 항목에서 실패해도 다음 항목을 이어 본다. 로그에는 번호와 점수만 남긴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemoryLinkDiscovery {

    private final MemoryLinkProperties properties;
    private final MemoryEmbeddingRepository embeddings;
    private final MemoryRepository memories;
    private final MemoryLinkService links;
    private final SupersedeCandidates supersedes;
    private final RelatedLinker related;
    private final PlatformTransactionManager transactionManager;


    /** 그 번호들의 항목마다 연결을 찾는다. */
    public void discover(Collection<Long> memoryIds) {
        if (memoryIds.isEmpty()) {
            return;
        }
        int proposed = 0;
        int relatedCount = 0;
        for (Long memoryId : memoryIds) {
            try {
                Found found = discoverOne(memoryId);
                proposed += found.proposed();
                relatedCount += found.related();
            } catch (RuntimeException ex) {
                log.warn("memory link discovery failed memoryId={} exceptionClass={}", memoryId, ex.getClass().getSimpleName());
            }
        }
        if (proposed > 0 || relatedCount > 0) {
            log.info("memory link discovery items={} proposed={} related={}", memoryIds.size(), proposed, relatedCount);
        }
    }


    private record Found(int proposed, int related) {
    }

    private record Neighborhood(MemoryLinkItem target, List<MemoryLinkItem> peers) {
    }


    private Found discoverOne(Long memoryId) {
        TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
        readOnly.setReadOnly(true);
        Neighborhood around = readOnly.execute(status -> neighborhood(memoryId));
        if (around == null) {
            return new Found(0, 0);
        }
        int proposed = 0;
        for (SupersedeCandidate candidate : supersedes.find(around.target(), around.peers())) {
            if (links.proposeAutoSupersede(candidate.newer().id(), candidate.older().id(), candidate.score()).isPresent()) {
                proposed++;
            }
        }
        int relatedCount = 0;
        for (RelatedPair pair : related.find(around.target(), around.peers())) {
            if (links.relateAuto(pair.target().id(), pair.peer().id(), pair.score(), properties.relatedMaxPerItem()).isPresent()) {
                relatedCount++;
            }
        }
        return new Found(proposed, relatedCount);
    }


    /** 그 항목과 같은 주인의, 대체되지 않은 승인 항목 가운데 벡터가 있는 것이다. 그 항목이 대상이 아니면 null 이다. */
    private Neighborhood neighborhood(Long memoryId) {
        Memory target = memories.findById(memoryId).orElse(null);
        if (target == null || target.status() != MemoryStatus.ACCEPTED || links.isSuperseded(memoryId)) {
            return null;
        }
        List<MemoryEmbedding> rows = target.scope() == MemoryScope.USER
            ? embeddings.findOfOwner(target.ownerUserId())
            : embeddings.findOfGroup(target.groupId());
        Set<Long> ids = rows.stream().map(MemoryEmbedding::memoryId).collect(Collectors.toSet());
        if (!ids.contains(memoryId)) {
            return null;
        }
        Set<Long> superseded = links.supersededBy(ids).keySet();
        Map<Long, Memory> byId = memories.findAllById(ids).stream()
            .filter(memory -> memory.status() == MemoryStatus.ACCEPTED && !superseded.contains(memory.id()))
            .collect(Collectors.toMap(Memory::id, Function.identity()));
        List<MemoryLinkItem> items = rows.stream()
            .filter(row -> byId.containsKey(row.memoryId()))
            .map(row -> item(byId.get(row.memoryId()), row))
            .toList();
        MemoryLinkItem self = items.stream().filter(item -> item.id().equals(memoryId)).findFirst().orElse(null);
        if (self == null) {
            return null;
        }
        return new Neighborhood(self, items.stream().filter(item -> !item.id().equals(memoryId)).toList());
    }


    private static MemoryLinkItem item(Memory memory, MemoryEmbedding row) {
        return new MemoryLinkItem(memory.id(), memory.updatedAt(), memory.title() + "\n" + memory.content(), row.vector());
    }
}
