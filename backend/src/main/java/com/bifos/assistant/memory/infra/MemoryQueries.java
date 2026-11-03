package com.bifos.assistant.memory.infra;

import com.bifos.assistant.memory.domain.entity.Memory;
import com.bifos.assistant.memory.domain.type.MemoryEntryType;
import com.bifos.assistant.memory.domain.type.MemoryRetrieval;
import com.bifos.assistant.memory.domain.type.MemoryScope;
import com.bifos.assistant.memory.domain.type.MemoryStatus;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.Set;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.springframework.data.jpa.domain.Specification;

/**
 * Memory 를 고르는 조건이다. 모두 읽어 온 뒤 거르지 않고 데이터베이스가 고르게 한다.
 *
 * <p>검색을 붙일 때도 범위와 collection 과 민감도 조건은 이 자리의 것을 그대로 쓴다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class MemoryQueries {

    /** 이 사용자가 볼 수 있는 항목이다. USER 는 주인만, GROUP 은 같은 그룹이 본다. 상태와 종류는 거르지 않는다. */
    public static Specification<Memory> readableBy(Long userId, Long groupId) {
        return (root, query, builder) -> readable(root, builder, userId, groupId);
    }


    /** 목록 API 가 내는 항목이다. 볼 수 있는 항목 가운데 종류가 MEMORY 인 것이다. 문서는 문서 API 가 따로 낸다(ADR-057). */
    public static Specification<Memory> listedFor(Long userId, Long groupId) {
        return (root, query, builder) -> builder.and(
            readable(root, builder, userId, groupId), builder.equal(root.get("entryType"), MemoryEntryType.MEMORY));
    }


    /**
     * 에이전트의 실행에 실을 수 있는 항목이다.
     *
     * <p>볼 수 있는 항목 가운데 승인됐고, 출처 원문이 아니며, 대체되지 않았고, 꺼내는 방식이 맞는 것만 고른다. 대체됐다는 것은
     * {@code superseded_by_memory_id} 가 채워졌다는 뜻이다(ADR-20261102 / table-reduce-r2). {@code collections} 가
     * null 이면 collection 을 거르지 않는다. 사용자가 자기 목록을 볼 때 쓴다.
     *
     * @param collections 에이전트가 받는 collection. null 이면 거르지 않는다
     */
    public static Specification<Memory> injectable(
        Long userId,
        Long groupId,
        MemoryRetrieval retrieval,
        Set<String> collections) {
        return (root, query, builder) -> {
            Predicate selected = builder.and(
                readable(root, builder, userId, groupId),
                builder.equal(root.get("status"), MemoryStatus.ACCEPTED),
                builder.notEqual(root.get("entryType"), MemoryEntryType.SOURCE),
                builder.isNull(root.get("supersededByMemoryId")),
                builder.equal(root.get("retrieval"), retrieval));
            if (collections == null) {
                return selected;
            }
            return builder.and(selected, root.get("collection").in(collections));
        };
    }


    private static Predicate readable(Root<Memory> root, CriteriaBuilder builder, Long userId, Long groupId) {
        Predicate own = builder.and(
            builder.equal(root.get("scope"), MemoryScope.USER), builder.equal(root.get("ownerUserId"), userId));
        if (groupId == null) {
            return own;
        }
        Predicate shared = builder.and(
            builder.equal(root.get("scope"), MemoryScope.GROUP), builder.equal(root.get("groupId"), groupId));
        return builder.or(own, shared);
    }
}
