package com.bifos.assistant.memory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

/**
 * 그룹이 쓰는 collection 하나다(ADR-052).
 *
 * <p>화면의 탭과 에이전트 접근 설정이 고를 목록이다. 누가 읽는지는 이 줄이 정하지 않는다. 그것은 범위와 에이전트의
 * 허용 목록이 정한다.
 */
@Entity
@Table(name = "memory_collection")
@Getter
@Accessors(fluent = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemoryCollection {

    /** 그룹마다 처음부터 두는 collection 의 key 다. 순서가 화면 순서다. */
    public static final List<String> DEFAULT_KEYS =
            List.of("core", "career", "learning", "health", "finance", "home", "identity");

    private static final List<String> DEFAULT_NAMES = List.of("기본", "커리어", "학습", "건강", "재무", "집", "신원");

    @EmbeddedId
    private MemoryCollectionId id;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    private MemoryCollection(Long groupId, String key, String displayName, int sortOrder, Instant now) {
        this.id = new MemoryCollectionId(groupId, key);
        this.displayName = displayName;
        this.sortOrder = sortOrder;
        this.createdAt = now;
    }

    /** 한 그룹의 기본 collection 줄들이다. */
    public static List<MemoryCollection> defaultsFor(Long groupId, Instant now) {
        return DEFAULT_KEYS.stream()
                .map(key -> {
                    int index = DEFAULT_KEYS.indexOf(key);
                    return new MemoryCollection(groupId, key, DEFAULT_NAMES.get(index), index + 1, now);
                })
                .toList();
    }

    public String key() {
        return id.key();
    }
}
