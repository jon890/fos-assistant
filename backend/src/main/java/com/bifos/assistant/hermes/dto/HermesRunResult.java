package com.bifos.assistant.hermes.dto;

/**
 * 끝난 실행 하나를 {@code GET /v1/runs/{run_id}} 에서 읽은 것이다.
 *
 * <p>{@code model} 은 실제로 돈 모델이 아니다. Hermes 는 실행을 만들 때 요청 본문의 값을 한 번 적고
 * 끝난 뒤에 다시 쓰지 않는다. 실제로 돈 모델은 {@link SessionRuntime} 이 갖는다.
 *
 * @param error 실패했을 때 Hermes 가 적은 글. 성공하면 비어 있다
 */
public record HermesRunResult(
        String runId,
        String sessionId,
        String status,
        String output,
        String model,
        String provider,
        String error,
        TokenUsage usage,
        SessionRuntime runtime) {

    /**
     * {@code runtime} 없이 만든다. v0.21.5 의 {@code runtime} 을 읽지 않는 자리와 테스트가 쓴다.
     *
     * <p>{@code runtime} 은 v0.21.5 실행 조회가 싣는 실제로 돈 provider 와 모델이다. {@code model}, {@code provider}
     * 와 달리 요청을 되돌려 준 값이 아니다. 없으면 null 이다.
     */
    public HermesRunResult(
            String runId,
            String sessionId,
            String status,
            String output,
            String model,
            String provider,
            String error,
            TokenUsage usage) {
        this(runId, sessionId, status, output, model, provider, error, usage, null);
    }

    /**
     * 그 provider 의 계정이 전부 막혔을 때 Hermes 가 붙이는 고정 접두사다.
     *
     * <p>{@code _ProviderAuthResolutionError} 하나만 이 경로를 타므로 판정 근거로 쓸 수 있다. 다른
     * 실패 글은 상류 provider 가 보낸 것이라 문구가 바뀔 수 있어 판정에 쓰지 않는다.
     */
    public static final String PROVIDER_AUTH_FAILED_PREFIX = "⚠️ Provider authentication failed:";

    /** 오류 글 없이 만든다. 실패 판정이 필요 없는 자리에서 쓴다. */
    public static HermesRunResult of(
            String runId,
            String sessionId,
            String status,
            String output,
            String model,
            String provider,
            TokenUsage usage) {
        return new HermesRunResult(runId, sessionId, status, output, model, provider, null, usage);
    }

    public boolean succeeded() {
        return "completed".equalsIgnoreCase(status);
    }

    /** 이 provider 의 계정이 전부 막혀 실패했는가. 그 실행은 {@code PROVIDER_BLOCKED} 로 남는다. */
    public boolean providerBlocked() {
        return !succeeded() && error != null && error.startsWith(PROVIDER_AUTH_FAILED_PREFIX);
    }
}
