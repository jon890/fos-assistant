package com.bifos.assistant.memory.domain;

import com.bifos.assistant.memory.domain.entity.Memory;
import com.bifos.assistant.memory.domain.type.MemoryRetrieval;
import java.util.regex.Pattern;

/** 항목을 어느 collection 에 어떤 방식으로 둘지다(ADR-052). */
public record MemoryPlacement(String collection, MemoryRetrieval retrieval) {

    /** collection key 는 소문자로 시작하고 소문자, 숫자, 하이픈만 쓴다. 64자까지다. */
    private static final Pattern COLLECTION_KEY = Pattern.compile("[a-z][a-z0-9-]{0,63}");


    public MemoryPlacement {
        if (collection == null || !COLLECTION_KEY.matcher(collection).matches()) {
            throw new IllegalArgumentException("a collection key is invalid");
        }
        if (retrieval == null) {
            throw new IllegalArgumentException("a retrieval is required");
        }
    }


    /** core 항목이다. 지금의 화면과 제안이 만드는 항목이 모두 이것이다. */
    public static MemoryPlacement core(MemoryRetrieval retrieval) {
        return new MemoryPlacement(Memory.DEFAULT_COLLECTION, retrieval);
    }


    public static boolean isCollectionKey(String value) {
        return value != null && COLLECTION_KEY.matcher(value).matches();
    }
}
