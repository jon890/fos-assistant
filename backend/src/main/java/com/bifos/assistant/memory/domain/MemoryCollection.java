package com.bifos.assistant.memory.domain;

import java.util.List;

/**
 * 그룹이 쓰는 collection 하나다(ADR-052).
 *
 * <p>화면의 탭과 에이전트 접근 설정이 고를 목록이다. 누가 읽는지는 이 값이 정하지 않는다. 그것은 범위와 에이전트의 허용
 * 목록이 정한다. 목록은 {@code group_setting} 의 {@code MEMORY_COLLECTIONS} 줄이 갖고, 줄이 없는 그룹은 {@link #DEFAULTS}
 * 를 쓴다.
 */
public record MemoryCollection(String key, String displayName) {

    /** 그룹마다 처음부터 두는 collection 이다. 순서가 화면 순서다. */
    public static final List<MemoryCollection> DEFAULTS = List.of(
        new MemoryCollection("core", "기본"),
        new MemoryCollection("career", "커리어"),
        new MemoryCollection("learning", "학습"),
        new MemoryCollection("health", "건강"),
        new MemoryCollection("finance", "재무"),
        new MemoryCollection("home", "집"),
        new MemoryCollection("identity", "신원"));

    /** {@link #DEFAULTS} 의 key 만 순서대로 모은 목록이다. */
    public static final List<String> DEFAULT_KEYS = DEFAULTS.stream().map(MemoryCollection::key).toList();
}
