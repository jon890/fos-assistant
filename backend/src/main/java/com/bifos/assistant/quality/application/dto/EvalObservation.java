package com.bifos.assistant.quality.application.dto;

import java.util.List;
import java.util.Set;

/**
 * 평가 문항 하나가 끝난 뒤 판정에 쓰는 값이다. 실행기가 평가 대화에서 모으고 {@code EvalJudge} 는 이 값만 본다.
 *
 * @param answer 마지막 답 글. 답이 없으면 빈 글
 * @param completedTools 실행 트리 전체에서 끝난 Control Plane 도구의 이름. 앞의 {@code mcp__fos_assistant__} 는 뗀다
 * @param instructions 답이 링크한 예약 작업들의 지시문
 * @param cardStatus 마지막으로 누른 승인 줄의 상태. 누른 줄이 없으면 null
 */
public record EvalObservation(String answer, Set<String> completedTools, List<String> instructions, String cardStatus) {

    public EvalObservation {
        answer = answer == null ? "" : answer;
        completedTools = completedTools == null ? Set.of() : Set.copyOf(completedTools);
        instructions = instructions == null ? List.of() : List.copyOf(instructions);
    }
}
