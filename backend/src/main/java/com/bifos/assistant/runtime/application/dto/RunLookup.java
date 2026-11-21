package com.bifos.assistant.runtime.application.dto;

/**
 * 실행 하나를 한 번 물은 답이다.
 *
 * <p>{@code FINISHED} 일 때만 {@code result} 가 있고, 나머지 둘은 null 이다.
 */
public record RunLookup(State state, RunResult result) {

    public enum State {
        /** 아직 끝나지 않았다. */
        RUNNING,
        /** 끝났다. 성공이든 실패든 {@code result} 가 그 끝을 담는다. */
        FINISHED,
        /** 런타임이 그 실행을 모른다. 다시 물을 곳이 없는 구현도 이렇게 답한다. */
        NOT_FOUND
    }


    public static RunLookup running() {
        return new RunLookup(State.RUNNING, null);
    }


    public static RunLookup finished(RunResult result) {
        return new RunLookup(State.FINISHED, result);
    }


    public static RunLookup notFound() {
        return new RunLookup(State.NOT_FOUND, null);
    }
}
