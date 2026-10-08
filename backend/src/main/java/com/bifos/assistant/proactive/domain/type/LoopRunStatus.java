package com.bifos.assistant.proactive.domain.type;

/** 매일 깨우기 살펴보기 하나를 잇는 시도의 상태다. {@code RUNNING} 이 아니면 끝난 시도이고 다시 열지 않는다. */
public enum LoopRunStatus {
    /** 시도 줄을 저장했고 평가와 판정이 아직 끝나지 않았다. */
    RUNNING,
    /** 평가와 판정을 마쳤다. */
    DECIDED,
    /** 평가하지 않고 건너뛰었다. 까닭은 {@link LoopSkippedReason} 이다. */
    SKIPPED,
    /** 평가나 판정이 실패했다. 까닭은 오류 코드 칸이 갖는다. */
    FAILED
}
