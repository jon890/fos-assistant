package com.bifos.assistant.memory.application.model;

import java.util.List;

/**
 * 관련도 순 검색 결과 한 페이지다.
 *
 * @param nextCursor 다음 페이지의 커서. 마지막 페이지면 null 이다
 * @param selector 순서를 정한 규칙의 이름과 버전. 로그에만 남긴다
 */
public record MemorySearchPage(List<MemorySearchItem> items, String nextCursor, String selector) {

    public MemorySearchPage {
        items = List.copyOf(items);
    }
}
