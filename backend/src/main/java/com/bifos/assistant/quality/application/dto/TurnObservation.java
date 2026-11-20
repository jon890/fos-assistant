package com.bifos.assistant.quality.application.dto;

import java.util.List;

/**
 * 확인 문항이 돈 turn 하나에서 판정에 쓰는 값이다. 다른 패키지의 타입에 기대지 않는다.
 *
 * @param answer 답 글. 없으면 빈 글
 * @param executionStatus 루트 실행 줄의 상태 이름
 * @param errorCode 실패면 오류 코드, 아니면 null
 * @param tools 트리 전체의 끝 사건
 * @param children 루트의 자식 실행
 * @param artifactKinds 답 메시지에 묶인 결과물 종류의 이름. 그림은 {@code IMAGE}
 * @param approvalRequested 쓰기 도구의 승인 카드가 떴는가
 */
public record TurnObservation(
    String answer,
    String executionStatus,
    String errorCode,
    List<ToolObservation> tools,
    List<ChildObservation> children,
    List<String> artifactKinds,
    boolean approvalRequested) {

    public TurnObservation {
        answer = answer == null ? "" : answer;
        tools = tools == null ? List.of() : List.copyOf(tools);
        children = children == null ? List.of() : List.copyOf(children);
        artifactKinds = artifactKinds == null ? List.of() : List.copyOf(artifactKinds);
    }
}
