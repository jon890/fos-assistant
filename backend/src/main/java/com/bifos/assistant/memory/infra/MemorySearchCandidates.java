package com.bifos.assistant.memory.infra;

import com.bifos.assistant.memory.domain.MemorySearchCandidate;
import com.bifos.assistant.memory.domain.entity.Memory;
import com.bifos.assistant.memory.domain.type.MemoryRetrieval;
import com.bifos.assistant.memory.domain.type.MemorySensitivity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Root;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * {@code memory_search} 의 후보를 읽는다. 권한 조건은 {@link MemoryQueries#injectable} 을 그대로 건다.
 *
 * <p>번호, 제목, revision, 고친 시각과 일반 본문만 고른다. 민감 항목과 암호화한 줄의 본문은 SQL 이 {@code NULL} 로 바꿔 앱까지 오지 않는다.
 * count 와 소유자를 읽지 않는다.
 */
@Component
@RequiredArgsConstructor
public class MemorySearchCandidates {

    private final EntityManager entityManager;


    /**
     * @param collections 에이전트가 받는 collection. null 이면 거르지 않는다
     * @param sensitiveCollections 그 가운데 민감 항목까지 받는 collection
     */
    public List<MemorySearchCandidate> candidatesFor(
        Long userId, Long groupId, Set<String> collections, Set<String> sensitiveCollections) {
        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<MemorySearchCandidate> query = builder.createQuery(MemorySearchCandidate.class);
        Root<Memory> root = query.from(Memory.class);
        Expression<String> plainContent = builder.<String>selectCase()
            .when(
                builder.and(
                    builder.isNull(root.get("contentKeyId")),
                    builder.equal(root.get("sensitivity"), MemorySensitivity.NORMAL)),
                root.<String>get("content"))
            .otherwise(builder.nullLiteral(String.class));
        query.select(builder.construct(
            MemorySearchCandidate.class,
            root.get("id"),
            root.get("title"),
            plainContent,
            root.get("revision"),
            root.get("updatedAt")))
            .where(MemoryQueries.injectable(userId, groupId, MemoryRetrieval.SEARCH, collections, sensitiveCollections)
                .toPredicate(root, query, builder))
            .orderBy(builder.asc(root.get("id")));
        return entityManager.createQuery(query).getResultList();
    }
}
