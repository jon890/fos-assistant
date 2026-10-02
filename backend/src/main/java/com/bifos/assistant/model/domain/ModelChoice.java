package com.bifos.assistant.model.domain;

import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.util.List;

/**
 * 대화에서 고른 provider, 모델, reasoning effort 다.
 *
 * <p>provider 와 모델은 함께 채우거나 함께 비운다. Hermes 는 둘을 함께 받아야 하기 때문이다. 셋 다 비면
 * 그 profile 의 기본값으로 돈다. 고른 모델이 Hermes 목록에 있는지는 여기서 보지 않는다. 나중에 목록에서
 * 빠져도 대화에 적힌 값을 그대로 보낸다.
 *
 * <p>만드는 길이 둘이다. 요청으로 들어온 값은 {@link #of} 로 만들어 검증하고, 대화에 이미 저장된 값은
 * {@link #stored} 로 검증 없이 읽는다. 검증 규칙이 나중에 바뀌어도 이미 저장된 대화의 목록 조회와 보내기가
 * 깨지지 않게 하려는 것이다. 두 길 모두 앞뒤 공백을 떼고 빈 값을 null 로 바꾼다.
 *
 * @param provider Hermes 가 아는 provider 이름. 기본 모델이면 null
 * @param model 그 provider 의 모델 이름. 기본 모델이면 null
 * @param reasoningEffort {@link #ACCEPTED_EFFORTS} 중 하나. 미지정이면 null 이고 {@code none}(reasoning 끄기)과 다르다
 */
public record ModelChoice(String provider, String model, String reasoningEffort) {

    /** 고를 수 있는 effort 다. 낮은 것부터 적는다. */
    public static final List<String> REASONING_EFFORTS = List.of("low", "medium", "high", "xhigh", "max");

    /** reasoning 을 끄는 effort 다. 그 모델의 끄기 지원을 확인해야 저장한다(ADR-060). */
    public static final String EFFORT_NONE = "none";

    /**
     * 요청으로 받는 effort 다. {@link #REASONING_EFFORTS} 에 {@link #EFFORT_NONE} 을 더한 것이다.
     *
     * <p>{@code minimal} 은 지원을 확인할 신호가 없어 받지 않는다. 그룹 단계 정의는 여전히 {@link #REASONING_EFFORTS}
     * 만 받는다.
     */
    public static final List<String> ACCEPTED_EFFORTS = List.of(EFFORT_NONE, "low", "medium", "high", "xhigh", "max");

    /** provider 이름의 최대 길이다. {@code conversation.model_provider} 열의 길이와 같다. */
    public static final int PROVIDER_MAX_LENGTH = 64;

    /** 모델 이름의 최대 길이다. {@code conversation.model} 열의 길이와 같다. */
    public static final int MODEL_MAX_LENGTH = 128;

    /** 공백만 정리한다. 검증은 {@link #of} 가 한다. */
    public ModelChoice {
        provider = blankToNull(provider);
        model = blankToNull(model);
        reasoningEffort = blankToNull(reasoningEffort);
    }

    /**
     * 요청으로 들어온 선택을 검증해 만든다.
     *
     * @throws ApiException {@code VALIDATION_FAILED}. provider 와 모델 중 하나만 있거나, 길이가 열보다 길거나,
     *     effort 가 {@link #ACCEPTED_EFFORTS} 에 없을 때
     */
    public static ModelChoice of(String provider, String model, String reasoningEffort) {
        ModelChoice choice = new ModelChoice(provider, model, reasoningEffort);
        if ((choice.provider == null) != (choice.model == null)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "provider and model must be chosen together");
        }
        if (choice.provider != null && choice.provider.length() > PROVIDER_MAX_LENGTH) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "provider must have at most " + PROVIDER_MAX_LENGTH + " characters");
        }
        if (choice.model != null && choice.model.length() > MODEL_MAX_LENGTH) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "model must have at most " + MODEL_MAX_LENGTH + " characters");
        }
        if (choice.reasoningEffort != null && !ACCEPTED_EFFORTS.contains(choice.reasoningEffort)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "unknown reasoning effort");
        }
        return choice;
    }

    /** 대화에 저장된 선택을 검증하지 않고 읽는다. 저장된 값은 그대로 싣는다. */
    public static ModelChoice stored(String provider, String model, String reasoningEffort) {
        return new ModelChoice(provider, model, reasoningEffort);
    }

    /** 아무것도 고르지 않은 상태다. 그 profile 의 기본 모델과 기본 effort 로 돈다. */
    public static ModelChoice defaults() {
        return new ModelChoice(null, null, null);
    }

    /** 모델을 고르지 않아 그 profile 의 기본 모델로 도는가. */
    public boolean usesDefaultModel() {
        return model == null;
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        return stripped.isEmpty() ? null : stripped;
    }
}
