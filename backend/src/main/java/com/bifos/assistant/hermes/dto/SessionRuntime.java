package com.bifos.assistant.hermes.dto;

/**
 * 세션 하나가 마지막으로 실제로 쓴 provider 와 모델이다.
 *
 * <p>두 곳에서 온다. {@code GET /api/sessions/{session_id}} 의 세션 행과, v0.21.5 {@code GET /v1/runs/{run_id}} 의
 * {@code runtime} 이다. 같은 실행 조회의 {@code model} 칸은 우리가 보낸 값을 되돌려 줄 뿐이라 넘김이 일어난 실행에서는
 * 실제와 어긋난다.
 *
 * @param model 실제로 돈 모델. 읽지 못하면 null
 * @param provider 실제로 돈 provider. 세션 조회가 주지 않으면 null
 */
public record SessionRuntime(String model, String provider) {
}
