package com.bifos.assistant.usage.application;

/**
 * 실행 하나에 실은 문맥 항목의 참조다. 실행 기록에 넘기고 실행 트리 응답에 싣는다.
 *
 * <p>{@code usage} 는 {@code context} 를 쓰지 못해(ADR-068) 문맥 묶음의 이름을 문자열로 받는다. 제목과 본문은 담지 않는다.
 *
 * @param source 항목의 출처 이름. {@code MEMORY_ALWAYS} 처럼 적는다
 * @param ref 원래 기록의 참조. Memory 는 {@code memory:<번호>} 다
 * @param bodyMode {@code INLINE}, {@code TITLE_ONLY}, {@code OMITTED}
 * @param freshness {@code FRESH}, {@code STALE}, {@code UNKNOWN}
 */
public record ContextSourceRef(String source, String ref, String bodyMode, String freshness) {}
