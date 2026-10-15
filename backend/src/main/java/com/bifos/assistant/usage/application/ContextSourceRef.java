package com.bifos.assistant.usage.application;

/**
 * 실행 하나에 실은 문맥 항목의 참조다. 실행 기록에 넘기고 실행 트리 응답에 싣는다.
 *
 * <p>{@code usage} 는 {@code context} 를 쓰지 못해(ADR-068) 문맥 묶음의 이름을 문자열로 받는다. 제목과 본문은 담지 않는다.
 *
 * <p>선택 근거는 그 항목을 어떤 규칙이 몇 번째로 골랐는지다(ADR-20261015 / memory-eval-gate). 사용 신호(읽혔는가, 고쳐졌는가)는 실행 뒤에
 * 오므로 담지 않는다.
 *
 * @param source 항목의 출처 이름. {@code MEMORY_ALWAYS} 처럼 적는다
 * @param ref 원래 기록의 참조. Memory 는 {@code memory:<번호>} 다
 * @param bodyMode {@code INLINE}, {@code TITLE_ONLY}, {@code OMITTED}
 * @param freshness {@code FRESH}, {@code STALE}, {@code UNKNOWN}
 * @param selector 고른 규칙의 이름과 버전. 없으면 null
 * @param selectionRank 같은 {@code source} 안에서 고른 순서. 1 부터 센다. 없으면 null
 * @param keywordScore 키워드 점수. 없으면 null
 * @param semanticScore 의미 점수. 없으면 null
 * @param selectionScore 합친 점수. 없으면 null
 */
public record ContextSourceRef(
    String source,
    String ref,
    String bodyMode,
    String freshness,
    String selector,
    Integer selectionRank,
    Double keywordScore,
    Double semanticScore,
    Double selectionScore) {

    /** 선택 근거가 없는 참조다. {@code MEMORY_READ} 줄과 Memory 가 아닌 항목이 쓴다. */
    public ContextSourceRef(String source, String ref, String bodyMode, String freshness) {
        this(source, ref, bodyMode, freshness, null, null, null, null, null);
    }
}
