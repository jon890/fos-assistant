package com.bifos.assistant.hermes.dto;

/**
 * 실행 하나를 한 번 물은 답이다.
 *
 * <p>{@code FINISHED} 일 때만 {@code result} 가 있고, 나머지 둘은 null 이다.
 */
public record HermesRunLookup(State state, HermesRunResult result) {

    public enum State {
        /** 아직 끝나지 않았다. 종료 상태가 아닌 모든 값이 여기에 든다. */
        RUNNING,
        /** 끝났다. 성공이든 실패든 {@code result} 가 그 끝을 담는다. */
        FINISHED,
        /** Hermes 가 그 run 을 모른다. */
        NOT_FOUND
    }

    public static HermesRunLookup running() {
        return new HermesRunLookup(State.RUNNING, null);
    }

    public static HermesRunLookup finished(HermesRunResult result) {
        return new HermesRunLookup(State.FINISHED, result);
    }

    public static HermesRunLookup notFound() {
        return new HermesRunLookup(State.NOT_FOUND, null);
    }
}
