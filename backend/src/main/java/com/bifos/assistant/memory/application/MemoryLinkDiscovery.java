package com.bifos.assistant.memory.application;

import com.bifos.assistant.memory.application.dto.MemoryLinkItem;
import com.bifos.assistant.memory.application.dto.RelatedPair;
import com.bifos.assistant.memory.application.dto.SupersedeCandidate;
import com.bifos.assistant.memory.application.relevance.MemoryVectorCache;
import com.bifos.assistant.memory.application.relevance.RelevanceCandidate;
import com.bifos.assistant.memory.config.MemoryLinkProperties;
import com.bifos.assistant.memory.domain.entity.Memory;
import com.bifos.assistant.memory.domain.entity.MemoryLink;
import com.bifos.assistant.memory.domain.type.MemoryScope;
import com.bifos.assistant.memory.domain.type.MemoryStatus;
import com.bifos.assistant.memory.infra.MemoryEmbeddingRepository;
import com.bifos.assistant.memory.infra.MemoryRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
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
    private final MemoryVectorCache vectorCache;
    private final PlatformTransactionManager transactionManager;


    /**
     * 그 번호들의 항목마다 연결을 찾는다. 같은 주인의 항목은 한 번만 읽는다. 모델을 바꾼 직후처럼 한 주기에 많은 항목을 다시 채워도 주인마다 이웃 목록을 한
     * 번 읽고 그 안에서 견준다.
     */
    public void discover(Collection<Long> memoryIds) {
        if (memoryIds.isEmpty()) {
            return;
        }
        Map<Owner, List<Long>> byOwner = readOnly().execute(status -> targetsByOwner(memoryIds));
        int proposed = 0;
        int relatedCount = 0;
        for (Map.Entry<Owner, List<Long>> entry : byOwner == null ? Map.<Owner, List<Long>>of().entrySet() : byOwner.entrySet()) {
            List<MemoryLinkItem> items;
            try {
                items = readOnly().execute(status -> itemsOf(entry.getKey()));
            } catch (RuntimeException ex) {
                log.warn("memory link discovery failed to read scope={} exceptionClass={}", entry.getKey().scope(), ex.getClass().getSimpleName());
                continue;
            }
            Map<Long, MemoryLinkItem> byId = new HashMap<>();
            items.forEach(item -> byId.put(item.id(), item));
            for (Long memoryId : entry.getValue()) {
                MemoryLinkItem target = byId.get(memoryId);
                if (target == null) {
                    continue;
                }
                try {
                    Found found = discoverOne(target, items.stream().filter(item -> !item.id().equals(memoryId)).toList());
                    proposed += found.proposed();
                    relatedCount += found.related();
                } catch (RuntimeException ex) {
                    log.warn("memory link discovery failed memoryId={} exceptionClass={}", memoryId, ex.getClass().getSimpleName());
                }
            }
        }
        if (proposed > 0 || relatedCount > 0) {
            log.info("memory link discovery items={} proposed={} related={}", memoryIds.size(), proposed, relatedCount);
        }
    }


    private record Found(int proposed, int related) {
    }

    /** 같은 항목끼리만 견주는 주인이다. {@code USER} 는 주인 사용자, {@code GROUP} 은 그룹 번호다. */
    private record Owner(MemoryScope scope, Long id) {
        static Owner of(Memory memory) {
            return new Owner(memory.scope(), memory.scope() == MemoryScope.USER ? memory.ownerUserId() : memory.groupId());
        }
    }


    private Found discoverOne(MemoryLinkItem target, List<MemoryLinkItem> peers) {
        int proposed = 0;
        for (SupersedeCandidate candidate : supersedes.find(target, peers)) {
            if (created(() -> links.proposeAutoSupersede(candidate.newer().id(), candidate.older().id(), candidate.score()))) {
                proposed++;
            }
        }
        int relatedCount = 0;
        for (RelatedPair pair : related.find(target, peers)) {
            if (created(() -> links.relateAuto(pair.target().id(), pair.peer().id(), pair.score(), properties.relatedMaxPerItem()))) {
                relatedCount++;
            }
        }
        return new Found(proposed, relatedCount);
    }


    /**
     * 연결 하나를 만든다. 에이전트의 {@code memory_link} 가 같은 쌍을 먼저 넣어 유일 제약에 걸리면 그 쌍만 건너뛰고 다음 쌍을 이어 본다. 같은 쌍은 이미
     * 있으므로 다시 만들 것이 없다.
     */
    private boolean created(Supplier<Optional<MemoryLink>> create) {
        try {
            return create.get().isPresent();
        } catch (DataIntegrityViolationException ex) {
            log.info("memory link discovery skipped an existing pair");
            return false;
        }
    }


    /** 그 번호 가운데 승인되고 대체되지 않은 항목을 주인마다 묶는다. */
    private Map<Owner, List<Long>> targetsByOwner(Collection<Long> memoryIds) {
        Set<Long> superseded = links.supersededBy(memoryIds).keySet();
        Map<Owner, List<Long>> byOwner = new LinkedHashMap<>();
        memories.findAllById(memoryIds).stream()
            .filter(memory -> memory.status() == MemoryStatus.ACCEPTED && !superseded.contains(memory.id()))
            .forEach(memory -> byOwner.computeIfAbsent(Owner.of(memory), owner -> new ArrayList<>()).add(memory.id()));
        return byOwner;
    }


    /** 그 주인의, 대체되지 않은 승인 항목 가운데 지금 모델과 지금 글에 맞는 벡터가 있는 것이다. */
    private List<MemoryLinkItem> itemsOf(Owner owner) {
        Set<Long> ids = Set.copyOf(owner.scope() == MemoryScope.USER
            ? embeddings.findMemoryIdsOfOwner(owner.id())
            : embeddings.findMemoryIdsOfGroup(owner.id()));
        if (ids.isEmpty()) {
            return List.of();
        }
        Set<Long> superseded = links.supersededBy(ids).keySet();
        List<Memory> eligible = memories.findAllById(ids).stream()
            .filter(memory -> memory.status() == MemoryStatus.ACCEPTED && !superseded.contains(memory.id()))
            .toList();
        // 모델이 바뀌는 동안이나 본문을 고친 뒤 다시 임베딩하기 전의 벡터는 지금 글과 맞지 않는다. 조립과 같이 모델과 지문이 맞는 벡터만 쓴다
        Map<Long, float[]> vectors = vectorCache.vectorsFor(eligible.stream()
            .map(memory -> new RelevanceCandidate(memory.id(), memory.title(), memory.content()))
            .toList());
        return eligible.stream()
            .filter(memory -> vectors.containsKey(memory.id()))
            .map(memory -> item(memory, vectors.get(memory.id())))
            .toList();
    }


    private TransactionTemplate readOnly() {
        TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
        readOnly.setReadOnly(true);
        return readOnly;
    }


    private static MemoryLinkItem item(Memory memory, float[] vector) {
        return new MemoryLinkItem(memory.id(), memory.updatedAt(), memory.title() + "\n" + memory.content(), vector);
    }
}
