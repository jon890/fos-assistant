package com.bifos.assistant.hermes.dto;

/**
 * 실행 하나를 Hermes 에 보내는 데 필요한 것이다.
 *
 * <p>{@code provider} 와 {@code model} 은 둘 다 채워야 한다. 하나만 보내면 Hermes 가 config 의 모델
 * 문자열을 새 provider 에 그대로 넘겨 {@code No LLM provider configured} 로 끝난다.
 *
 * @param profileName 이 turn 을 도는 Hermes profile. credential 과 우리가 제시할 API key 도 이것이
 *     정한다
 * @param apiBaseUrl 그 profile 의 API server 가 답하는 주소. {@code /v1} 앞까지다
 * @param input 사용자가 보낸 글
 * @param instructions 에이전트의 프롬프트 위에 얹는 글. 요청자가 볼 수 있는 Memory 를 Control Plane
 *     이 여기에 싣는다
 * @param sessionId 이어 갈 Hermes session. 새로 시작하면 null
 * @param provider 이 실행에 쓸 provider
 * @param model 이 실행에 쓸 모델
 */
public record HermesRunCommand(
        String profileName,
        String apiBaseUrl,
        String input,
        String instructions,
        String sessionId,
        String provider,
        String model) {
}
