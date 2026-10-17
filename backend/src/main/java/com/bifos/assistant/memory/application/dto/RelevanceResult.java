package com.bifos.assistant.memory.application.dto;

import java.util.List;

/**
 * 관련도 계산의 결과다.
 *
 * @param selector 순서를 정한 규칙의 이름과 버전. 값의 뜻은 {@code ContextSelection} 의 Javadoc 이 갖는다
 * @param ranked 관련 있는 항목. 점수 내림차순, 같으면 번호 오름차순이다
 */
public record RelevanceResult(String selector, List<RelevanceScore> ranked) {

    public RelevanceResult {
        ranked = List.copyOf(ranked);
    }
}
