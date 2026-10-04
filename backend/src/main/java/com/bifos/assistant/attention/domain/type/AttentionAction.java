package com.bifos.assistant.attention.domain.type;

/** 사용자가 한 카드의 한 항목에 건 제어다. {@code attention_control.action} 이 이 이름을 저장한다. */
public enum AttentionAction {
    /** 그 상태가 바뀔 때까지 숨긴다. */
    HIDE,
    /** 정한 시각까지 미룬다. */
    SNOOZE
}
