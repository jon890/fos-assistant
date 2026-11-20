package com.bifos.assistant.quality.domain.type;

/** 확인 한 번의 상태다. 끝난 상태는 다시 바뀌지 않는다. */
public enum QualityRunStatus {
    RUNNING,
    PASSED,
    FAILED
}
