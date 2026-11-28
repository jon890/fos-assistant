package com.bifos.assistant.connector.handoff.application.dto;

/** 넘기기 카드의 단추가 고른 것이다. 「다 했어요」 와 「결제했어요」 는 {@link #DONE}, 「그만할래요」 와 「사지 않을래요」 는 {@link #STOP} 이다. */
public enum HandoffCloseOutcome {
    DONE,
    STOP
}
