package com.bifos.assistant.hermes.dto;

/**
 * 한 모델의 reasoning 지원과 reasoning 끄기 지원이다(ADR-060).
 *
 * <p>Hermes 가 밝힌 값만 {@code SUPPORTED} 나 {@code UNSUPPORTED} 로 두고, 밝히지 않은 칸은 {@code UNKNOWN} 이다.
 * 우리가 없는 값을 참으로 채우지 않는다.
 *
 * @param support Hermes 의 {@code capabilities.<모델>.reasoning} 을 읽은 값
 * @param disable reasoning 을 끌 수 있는가. Hermes 의 {@code capabilities.<모델>.can_disable_reasoning} 을 읽은 값
 */
public record ReasoningCapability(Support support, Support disable) {

    /** 지원 여부다. Hermes 가 밝히지 않았으면 {@code UNKNOWN} 이다. */
    public enum Support {
        SUPPORTED,
        UNSUPPORTED,
        UNKNOWN
    }

    /** Hermes 가 아무것도 밝히지 않은 모델이다. */
    public static final ReasoningCapability UNKNOWN_ALL = new ReasoningCapability(Support.UNKNOWN, Support.UNKNOWN);
}
