package com.bifos.assistant.usage.domain.type;

/** 실행 성공 여부와 별개인 사건 스트림의 관측 범위다. 자식 수나 비용의 확정을 뜻하지 않는다. */
public enum EventObservation {
    UNKNOWN,
    OBSERVING,
    OBSERVED,
    INCOMPLETE
}
