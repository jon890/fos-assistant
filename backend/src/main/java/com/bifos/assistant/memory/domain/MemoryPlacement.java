package com.bifos.assistant.memory.domain;

import com.bifos.assistant.memory.domain.type.MemoryRetrieval;
import com.bifos.assistant.memory.domain.type.MemorySensitivity;
import java.util.regex.Pattern;

/**
 * 항목을 어느 collection 에 어떤 방식과 민감도로 둘지다(ADR-051).
 *
 * <p>민감 항목은 항상 싣지 못한다. 항상 층은 고른 항목의 본문이 곧 프롬프트다. 저장할 때 막아 두면 조립 판정이 한 번
 * 틀려도 민감 본문이 나가지 않는다.
 */
public record MemoryPlacement(String collection, MemoryRetrieval retrieval, MemorySensitivity sensitivity) {

    /** collection key 는 소문자로 시작하고 소문자, 숫자, 하이픈만 쓴다. 64자까지다. */
    private static final Pattern COLLECTION_KEY = Pattern.compile("[a-z][a-z0-9-]{0,63}");

    public MemoryPlacement {
        if (collection == null || !COLLECTION_KEY.matcher(collection).matches()) {
            throw new IllegalArgumentException("a collection key is invalid");
        }
        if (retrieval == null || sensitivity == null || !allows(retrieval, sensitivity)) {
            throw new IllegalArgumentException("a sensitive memory cannot always be injected");
        }
    }

    /** 민감하지 않은 core 항목이다. 지금의 화면과 제안이 만드는 항목이 모두 이것이다. */
    public static MemoryPlacement core(MemoryRetrieval retrieval) {
        return new MemoryPlacement(Memory.DEFAULT_COLLECTION, retrieval, MemorySensitivity.NORMAL);
    }

    public static boolean isCollectionKey(String value) {
        return value != null && COLLECTION_KEY.matcher(value).matches();
    }

    /** 이 방식과 민감도를 함께 둘 수 있는가. */
    public static boolean allows(MemoryRetrieval retrieval, MemorySensitivity sensitivity) {
        return !(retrieval == MemoryRetrieval.ALWAYS && sensitivity == MemorySensitivity.SENSITIVE);
    }
}
