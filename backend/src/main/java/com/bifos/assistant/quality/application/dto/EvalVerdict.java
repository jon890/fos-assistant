package com.bifos.assistant.quality.application.dto;

import java.util.List;

/**
 * 평가 문항 하나의 규칙 판정이다. 통과면 까닭이 비어 있다.
 *
 * @param failures 어긋난 기대의 고정 코드와 대상 이름({@code 코드:대상}). 답 본문은 담지 않는다
 */
public record EvalVerdict(boolean passed, List<String> failures) {

    public EvalVerdict {
        failures = List.copyOf(failures);
    }
}
