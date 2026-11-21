package com.bifos.assistant.quality.domain.type;

import com.fasterxml.jackson.annotation.JsonProperty;

/** 평가 문항의 한 차례가 띄운 승인 카드를 실행기가 어떻게 할지다(docs/features/agent-quality/spec.md 의 REQ16). */
public enum CardAction {
    /** 화면의 승인 단추와 같은 길로 승인한다. */
    @JsonProperty("approve")
    APPROVE,
    /** 화면의 거절 단추와 같은 길로 거절한다. */
    @JsonProperty("reject")
    REJECT,
    /** 누르지 않는다. 판정 뒤 비우기가 거절로 닫는다. 적지 않은 차례에 카드가 뜨면 이것으로 본다. */
    @JsonProperty("leave")
    LEAVE
}
