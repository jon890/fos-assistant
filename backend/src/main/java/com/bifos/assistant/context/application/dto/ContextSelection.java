package com.bifos.assistant.context.application.dto;

/**
 * 문맥 항목 하나를 어떤 규칙이 몇 번째로 골랐는지다(ADR-20261015 / memory-eval-gate). 실행 기록의 선택 근거 칸에 그대로 옮긴다.
 *
 * <p>이 Javadoc 이 {@code selector} 값 목록의 단일 소스다. 같은 이름의 규칙을 바꾸면 버전을 올려, 운영 집계가 앞뒤를 가르게 한다.
 *
 * <ul>
 * <li>{@value #ALWAYS_BY_ID}: 항상 층. 그룹 항목 다음 개인 항목을 범위마다 번호 순으로 싣는다
 * <li>{@value #FACTS_RECENT}: 개인 사실 구역. 최근에 고친 순으로 고르고 번호 순으로 싣는다
 * <li>{@value #INDEX_BY_ID}: 색인 층. 번호 순으로 싣는다. 질문이 있으면 점수가 없는 항목에 붙고, 점수가 있는 항목 뒤에 번호 순으로 싣는다
 * <li>{@value #SEARCH_KEYWORD}: 질문과 관련된 기억 구역. 키워드 점수 순으로 고르고 그 차례로 싣는다. 순위는 관련 있는 항목 전체 안의 차례다
 * <li>{@value #INDEX_KEYWORD}: 색인 층 가운데 키워드 점수가 있는 항목. 구역에 싣지 못한 항목을 점수 순으로 색인 맨 앞에 싣는다
 * <li>{@value #SEARCH_HYBRID}: 질문과 관련된 기억 구역. 키워드 순위와 의미 순위를 RRF 로 합친 점수 순으로 고른다. 질문 임베딩이 실패하면 {@value #SEARCH_KEYWORD} 로 물러난다
 * <li>{@value #INDEX_HYBRID}: 색인 층 가운데 합친 점수가 있는 항목. 구역에 싣지 못한 항목을 합친 점수 순으로 색인 맨 앞에 싣는다
 * <li>{@value #LINK_RELATED}: 연결된 기억 구역. 개인 사실 구역과 질문 구역에 실린 항목과 효력 있는 {@code RELATED} 로 이어진 이웃을 사람과 에이전트가 맺은
 * 연결 먼저, 그다음 코드가 맺은 연결의 유사도 순으로 싣는다. 점수 칸에는 그 유사도를 적는다
 * </ul>
 *
 * @param selector 고른 규칙의 이름과 버전. 32자 이하다
 * @param rank 같은 실행의 같은 {@code source} 안에서 고른 순서. 1 부터 센다. 자리가 없어 빠진 항목도 받는다. 개인 사실 구역은 싣는 차례(번호 순)가
 * 아니라 고른 차례(최근에 고친 순)다
 * @param keywordScore 키워드 점수. 점수 없이 고르는 규칙은 null
 * @param semanticScore 의미 점수. 점수 없이 고르는 규칙은 null
 * @param score 합친 점수. 점수 없이 고르는 규칙은 null
 */
public record ContextSelection(String selector, int rank, Double keywordScore, Double semanticScore, Double score) {


    public static final String ALWAYS_BY_ID = "always-id-v1";

    public static final String FACTS_RECENT = "facts-recent-v1";

    public static final String INDEX_BY_ID = "index-id-v1";

    public static final String SEARCH_KEYWORD = "search-keyword-v1";

    public static final String INDEX_KEYWORD = "index-keyword-v1";

    public static final String SEARCH_HYBRID = "search-hybrid-v1";

    public static final String INDEX_HYBRID = "index-hybrid-v1";

    public static final String LINK_RELATED = "link-related-v1";

    /** 질문과 관련된 기억 구역의 규칙에 짝지은 색인의 규칙이다. */
    public static String indexSelectorOf(String searchSelector) {
        if (SEARCH_KEYWORD.equals(searchSelector)) {
            return INDEX_KEYWORD;
        }
        if (SEARCH_HYBRID.equals(searchSelector)) {
            return INDEX_HYBRID;
        }
        throw new IllegalArgumentException("unknown search selector: " + searchSelector);
    }


    /** 점수 없이 순서만으로 고른 근거다. */
    public static ContextSelection ranked(String selector, int rank) {
        return new ContextSelection(selector, rank, null, null, null);
    }
}
