package com.bifos.assistant.chat.domain;

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
 * @param provider Hermes 가 아는 provider 이름. 기본 모델이면 null
 * @param model 그 provider 의 모델 이름. 기본 모델이면 null
 * @param reasoningEffort {@link #REASONING_EFFORTS} 중 하나. 기본값이면 null
 */
public record ModelChoice(String provider, String model, String reasoningEffort) {

    /** 고를 수 있는 effort 다. 낮은 것부터 적는다. */
    public static final List<String> REASONING_EFFORTS = List.of("low", "medium", "high", "xhigh", "max");

    public ModelChoice {
        provider = blankToNull(provider);
        model = blankToNull(model);
        reasoningEffort = blankToNull(reasoningEffort);
        if ((provider == null) != (model == null)) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "provider and model must be chosen together");
        }
        if (reasoningEffort != null && !REASONING_EFFORTS.contains(reasoningEffort)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "unknown reasoning effort");
        }
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
