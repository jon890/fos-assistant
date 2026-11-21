package com.bifos.assistant.runtime.application.dto;

import com.bifos.assistant.runtime.domain.RuntimeTarget;
import java.util.List;

/**
 * 실행 하나를 런타임에 보내는 데 필요한 것이다.
 *
 * <p>{@code provider} 와 {@code model} 은 둘을 함께 채우거나 함께 비운다. 비우면 런타임의 기본값으로 돈다. 어긋난 조합을
 * 어떻게 다루는지는 구현이 정한다.
 *
 * @param target 이 turn 이 도는 곳
 * @param input 에이전트에게 보내는 글
 * @param instructions 에이전트의 프롬프트 위에 얹는 글. 요청자가 볼 수 있는 Memory 를 Control Plane 이 여기에 넣는다
 * @param sessionId 이어 갈 런타임 session. 새로 시작하면 null
 * @param provider 이 실행에 쓸 provider
 * @param model 이 실행에 쓸 모델
 * @param reasoningEffort 이 실행에 쓸 reasoning effort. 비우면 런타임이 그 모델의 설정값을 쓴다
 * @param images 입력 글 뒤에 함께 보내는 사진. 비면 글만 보낸다
 */
public record RunCommand(
    RuntimeTarget target,
    String input,
    String instructions,
    String sessionId,
    String provider,
    String model,
    String reasoningEffort,
    List<RunImage> images) {

    public RunCommand {
        images = images == null ? List.of() : List.copyOf(images);
    }


    /** 사진 없이 글만 보내는 실행이다. */
    public RunCommand(
        RuntimeTarget target,
        String input,
        String instructions,
        String sessionId,
        String provider,
        String model,
        String reasoningEffort) {
        this(target, input, instructions, sessionId, provider, model, reasoningEffort, List.of());
    }
}
