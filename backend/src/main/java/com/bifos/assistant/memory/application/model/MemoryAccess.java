package com.bifos.assistant.memory.application.model;

import com.bifos.assistant.memory.domain.type.MemorySensitivity;
import java.util.Set;

/**
 * 한 실행이 받을 수 있는 collection 이다(ADR-053).
 *
 * <p>범위 판정(USER 의 주인, GROUP 의 같은 그룹)과는 따로다. 이 값은 그 위에 collection 과 민감도를 더 거른다.
 *
 * @param unrestricted 참이면 collection 과 민감도를 거르지 않는다. 사용자가 자기 Memory 목록을 볼 때만 쓴다
 * @param collections 받는 collection 의 key
 * @param sensitiveCollections 그 가운데 민감 항목까지 받는 collection 의 key
 */
public record MemoryAccess(boolean unrestricted, Set<String> collections, Set<String> sensitiveCollections) {

    public MemoryAccess {
        collections = Set.copyOf(collections);
        sensitiveCollections = Set.copyOf(sensitiveCollections);
    }

    /** 아무것도 받지 않는다. 커넥터 에이전트와 에이전트를 알 수 없는 실행이 이것이다. */
    public static MemoryAccess none() {
        return new MemoryAccess(false, Set.of(), Set.of());
    }

    /** 사용자 본인이 화면에서 보는 범위다. 에이전트의 실행에는 쓰지 않는다. */
    public static MemoryAccess owner() {
        return new MemoryAccess(true, Set.of(), Set.of());
    }

    public static MemoryAccess of(Set<String> collections, Set<String> sensitiveCollections) {
        return new MemoryAccess(false, collections, sensitiveCollections);
    }

    /** 받는 것이 하나도 없는가. */
    public boolean isEmpty() {
        return !unrestricted && collections.isEmpty();
    }

    public boolean allows(String collection, MemorySensitivity sensitivity) {
        if (unrestricted) {
            return true;
        }
        return collections.contains(collection)
                && (sensitivity == MemorySensitivity.NORMAL || sensitiveCollections.contains(collection));
    }
}
