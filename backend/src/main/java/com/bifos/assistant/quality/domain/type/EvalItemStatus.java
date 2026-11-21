package com.bifos.assistant.quality.domain.type;

/** 평가 문항 하나의 결과다. */
public enum EvalItemStatus {
    /** 보내기 시작했고 아직 판정하지 않았다. 재기동으로 끊기면 {@link #FAILED} 로 끝낸다. */
    RUNNING,
    PASSED,
    FAILED,
    /** 문항 상한을 넘었다. */
    TIMED_OUT,
    /** 채울 자리 값이 없어 돌리지 않았다. 묶음 결과의 분모에서 빠진다. */
    SKIPPED
}
