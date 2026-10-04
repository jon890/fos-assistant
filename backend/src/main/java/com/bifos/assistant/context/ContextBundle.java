package com.bifos.assistant.context;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 실행 하나에 모은 문맥 항목이다. 요청마다 만들고 저장하지 않는다(ADR-071).
 *
 * <p>항목 순서는 글에 실은 순서이고, 자리가 없어 빠진 항목도 {@link ContextBodyMode#OMITTED} 로 원래 자리에 둔다.
 */
public record ContextBundle(List<ContextItem> items) {

    private static final ContextBundle EMPTY = new ContextBundle(List.of());

    public ContextBundle {
        items = items == null ? List.of() : List.copyOf(items);
    }

    /** 항목이 하나도 없는 묶음이다. */
    public static ContextBundle empty() {
        return EMPTY;
    }

    /** 제목과 본문이 로그에 남지 않게 항목 수와 각 항목의 {@code source:ref} 만 낸다. */
    @Override
    public String toString() {
        return items.stream()
                .map(item -> item.source() + ":" + item.ref())
                .collect(Collectors.joining(", ", "ContextBundle[size=" + items.size() + ", items=[", "]]"));
    }
}
