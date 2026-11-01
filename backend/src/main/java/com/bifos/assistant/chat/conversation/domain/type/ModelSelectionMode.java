package com.bifos.assistant.chat.conversation.domain.type;

/**
 * 대화가 모델을 고르는 방법이다. 고르지 않은 대화는 이 값이 비어 있다.
 *
 * <p>사용자는 슬라이더 칸만 고른다(ADR-20261101 / model-setting). 직접 고르던 {@code CUSTOM} 은 없앴고 저장돼 있던 값은
 * 이관에서 선택 없음으로 바꿨다.
 */
public enum ModelSelectionMode {
    TIER
}
