package com.bifos.assistant.hermes.dto;

import java.util.List;

/**
 * 실행 하나를 Hermes 에 보내는 데 필요한 것이다.
 *
 * <p>{@code provider} 와 {@code model} 은 둘을 함께 채우거나 함께 비운다. 비우면 profile 의 기본값으로
 * 돈다. 하나만 보내면 Hermes 가 config 의 모델 문자열을 새 provider 에 그대로 넘겨
 * {@code No LLM provider configured} 로 끝난다.
 *
 * @param profileName 이 turn 을 도는 Hermes profile. credential 과 우리가 제시할 API key 도 이것이
 *     정한다
 * @param apiBaseUrl 그 profile 의 API server 가 답하는 주소. {@code /v1} 앞까지다
 * @param input 에이전트에게 보내는 글
 * @param instructions 에이전트의 프롬프트 위에 얹는 글. 요청자가 볼 수 있는 Memory 를 Control Plane
 *     이 여기에 싣는다
 * @param sessionId 이어 갈 Hermes session. 새로 시작하면 null
 * @param provider 이 실행에 쓸 provider
 * @param model 이 실행에 쓸 모델
 * @param reasoningEffort 이 실행에 실을 reasoning effort. 받은 값을 그대로 싣고, 비우면 Hermes 가 그
 *     모델의 설정값을 쓴다
 * @param images 입력 글 뒤에 이미지로 함께 싣는 사진. 비면 입력을 글 하나로 보낸다. 근거는 ADR-20261009 /
 *     native-image-input 에 있다
 */
public record HermesRunCommand(
        String profileName,
        String apiBaseUrl,
        String input,
        String instructions,
        String sessionId,
        String provider,
        String model,
        String reasoningEffort,
        List<HermesImage> images) {

    public HermesRunCommand {
        images = images == null ? List.of() : List.copyOf(images);
    }

    /** 사진 없이 글만 보내는 실행이다. */
    public HermesRunCommand(
            String profileName,
            String apiBaseUrl,
            String input,
            String instructions,
            String sessionId,
            String provider,
            String model,
            String reasoningEffort) {
        this(profileName, apiBaseUrl, input, instructions, sessionId, provider, model, reasoningEffort, List.of());
    }
}
