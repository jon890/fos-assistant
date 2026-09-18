package com.bifos.assistant.hermes.dto;

/**
 * 세션 하나가 마지막으로 실제로 쓴 provider 와 모델이다.
 *
 * <p>{@code GET /api/sessions/{session_id}} 만 이 값을 갖는다. {@code GET /v1/runs/{run_id}} 의
 * {@code model} 은 우리가 보낸 값을 되돌려 줄 뿐이라, 넘김이 일어난 실행에서는 실제와 어긋난다.
 *
 * @param model 실제로 돈 모델. 읽지 못하면 null
 * @param provider 실제로 돈 provider. 세션 조회가 주지 않으면 null
 */
public record SessionRuntime(String model, String provider) {
}
