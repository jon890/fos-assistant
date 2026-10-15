package com.bifos.assistant.context;

/**
 * 문맥 항목 하나를 어떤 규칙이 몇 번째로 골랐는지다(ADR-20261015 / memory-eval-gate). 실행 기록의 선택 근거 칸에 그대로 옮긴다.
 *
 * <p>이 Javadoc 이 {@code selector} 값 목록의 단일 소스다. 같은 이름의 규칙을 바꾸면 버전을 올려, 운영 집계가 앞뒤를 가르게 한다.
 *
 * <ul>
 * <li>{@value #ALWAYS_BY_ID}: 항상 층. 그룹 항목 다음 개인 항목을 범위마다 번호 순으로 싣는다
 * <li>{@value #FACTS_RECENT}: 개인 사실 구역. 최근에 고친 순으로 고르고 번호 순으로 싣는다
 * <li>{@value #INDEX_BY_ID}: 색인 층. 번호 순으로 싣는다
 * </ul>
 *
 * @param selector 고른 규칙의 이름과 버전. 32자 이하다
 * @param rank 같은 실행의 같은 {@code source} 안에서 고른 순서. 1 부터 센다. 자리가 없어 빠진 항목도 받는다
 * @param keywordScore 키워드 점수. 점수 없이 고르는 규칙은 null
 * @param semanticScore 의미 점수. 점수 없이 고르는 규칙은 null
 * @param score 합친 점수. 점수 없이 고르는 규칙은 null
 */
public record ContextSelection(String selector, int rank, Double keywordScore, Double semanticScore, Double score) {


    public static final String ALWAYS_BY_ID = "always-id-v1";

    public static final String FACTS_RECENT = "facts-recent-v1";

    public static final String INDEX_BY_ID = "index-id-v1";

    /** 점수 없이 순서만으로 고른 근거다. */
    public static ContextSelection ranked(String selector, int rank) {
        return new ContextSelection(selector, rank, null, null, null);
    }
}
