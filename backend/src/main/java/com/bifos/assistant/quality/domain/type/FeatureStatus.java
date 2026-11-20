package com.bifos.assistant.quality.domain.type;

/** 기능 하나의 확인 결과다. */
public enum FeatureStatus {
    PASSED,
    FAILED,
    /** 기능 마감을 넘겼거나 Hermes 를 기다리다 시간이 다 됐다. */
    TIMED_OUT
}
