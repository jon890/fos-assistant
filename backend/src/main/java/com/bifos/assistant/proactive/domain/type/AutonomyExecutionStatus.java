package com.bifos.assistant.proactive.domain.type;

/** {@code EXECUTE} 판정이 잡은 자동 실행의 상태다. 어느 상태에서도 다시 시작하지 않는다. */
public enum AutonomyExecutionStatus {
    /** 실행 키를 저장했고 아직 시작하지 않았다. */
    PENDING,
    /** 읽기 전용 살펴보기를 시작했다. */
    STARTED,
    /** 시작 경로가 거절했다. */
    FAILED
}
