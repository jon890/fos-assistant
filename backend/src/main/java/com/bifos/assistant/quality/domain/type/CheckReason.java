package com.bifos.assistant.quality.domain.type;

/** 기능 확인이 통과하지 못한 까닭의 고정 코드다. 모델이 쓴 글을 결과에 담지 않으려고 코드로만 남긴다. */
public enum CheckReason {
    /** 기능 마감을 넘겼다. */
    TIMED_OUT,
    /** Hermes 를 기다리는 상한에 걸렸다. */
    HERMES_TIMEOUT,
    /** 실행이 실패했다. */
    RUN_FAILED,
    /** 쓰기 도구의 승인 카드가 떴다. 확인 문항은 승인을 기다리지 않는다. */
    APPROVAL_REQUESTED,
    /** 사용자 실행 한도나 대화 잠금에 걸렸다. */
    BUSY,
    /** 서버가 다시 떠 확인이 끊겼다. */
    INTERRUPTED,
    /** 검색 도구가 끝나지 않았거나 실패했다. */
    NO_SEARCH,
    /** 답 끝 「출처」 절에 링크가 없다. */
    NO_SOURCES,
    /** 위임 자식 실행이 없다. */
    NO_CHILD,
    /** 위임 자식 실행이 성공하지 못했다. */
    CHILD_FAILED,
    /** 위임 결과가 대화에 전해지지 않았다. */
    NOT_DELIVERED,
    /** 파일 공간에 넣은 보관 줄이 없다. */
    NO_VAULT_ROW,
    /** 답에 그림 결과물이 묶이지 않았다. */
    NO_IMAGE,
    /** 미리 넣은 기억의 낱말이 답에 없다. */
    FACT_MISSING,
    /** 예약 작업 발화가 성공으로 끝나지 않았다. */
    TASK_NOT_SUCCEEDED,
    /** 원본 사진 조회 도구가 끝나지 않았거나 실패했다. */
    NO_INSPECT,
    /** 사진에 심은 글자가 답에 없다. */
    MARKER_MISSING,
    /** 도구 이름을 적지 않은 문항에서 Control Plane 도구를 찾아 부르지 않았다. */
    NO_TOOL,
    /**
     * 평가 비서의 Control Plane MCP 연결이 끊긴 채 돌았다. 에이전트 품질이 아니라 평가 환경의 문제다(docs/features/agent-quality/design.md 의 「평가 환경
     * 오류」).
     */
    ENVIRONMENT
}
