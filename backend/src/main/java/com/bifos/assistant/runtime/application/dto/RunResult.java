package com.bifos.assistant.runtime.application.dto;

import com.bifos.assistant.runtime.domain.TokenUsage;

/**
 * 끝난 실행 하나의 결과다.
 *
 * <p>{@code model} 과 {@code provider} 는 요청에 적은 값이고 실제로 돈 모델은 {@link SessionRuntime} 이 갖는다.
 *
 * @param outcome 성공, 실패, 취소 가운데 무엇으로 끝났는지
 * @param statusLabel 런타임이 적은 종료 상태 글자. 실행 기록의 오류 코드와 로그에 그대로 쓴다. 분기에는 {@code outcome} 을
 * 쓴다
 * @param error 실패했을 때 런타임이 적은 글. 성공하면 비어 있다
 * @param pendingSteer 끼워 넣었지만 모델에 넣지 못하고 끝나 돌아온 글. 없으면 null 이다
 * @param pendingSteerKnown {@code pendingSteer} 를 아는가. 거짓이면 넣지 못한 글이 있었는지 모른다
 * @param providerBlocked 그 provider 의 계정이 전부 막혀 실패했는가
 */
public record RunResult(
    String runId,
    String sessionId,
    RunOutcome outcome,
    String statusLabel,
    String output,
    String model,
    String provider,
    String error,
    TokenUsage usage,
    SessionRuntime runtime,
    String pendingSteer,
    boolean pendingSteerKnown,
    boolean providerBlocked) {

    public boolean succeeded() {
        return outcome == RunOutcome.SUCCEEDED;
    }
}
