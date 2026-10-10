package com.bifos.assistant.memory.application.model;

import java.util.List;

/** 번호 순서의 검색 결과와 다음 페이지의 시작 번호다. 마지막 페이지의 번호는 null이다. */
public record MemorySearchPage(List<MemorySearchItem> items, Long nextAfterId) {}
