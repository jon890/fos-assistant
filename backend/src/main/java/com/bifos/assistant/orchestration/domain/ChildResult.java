package com.bifos.assistant.orchestration.domain;

/**
 * 실행 하나의 결과. 실패해도 실행 줄은 남는다.
 *
 * <p>실패를 예외로 올리지 않고 값으로 돌려준다. 흐름이 한쪽 실패로 끊기지 않고 나머지를 끝까지
 * 기다린 뒤 판정하게 하기 위해서다.
 *
 * @param executionId 이 실행이 남긴 {@code agent_execution} 의 번호
 * @param succeeded 실행이 SUCCEEDED 로 끝났는지
 * @param output 성공했을 때의 답. 실패하면 null 이다
 * @param errorCode 실패했을 때 실행 줄에 적은 값. 성공하면 null 이다
 */
public record ChildResult(Long executionId, boolean succeeded, String output, String errorCode) {

    public static ChildResult succeeded(Long executionId, String output) {
        return new ChildResult(executionId, true, output == null ? "" : output, null);
    }

    public static ChildResult failed(Long executionId, String errorCode) {
        return new ChildResult(executionId, false, null, errorCode);
    }
}
