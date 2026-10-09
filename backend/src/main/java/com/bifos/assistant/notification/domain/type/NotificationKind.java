package com.bifos.assistant.notification.domain.type;

/**
 * 알림의 종류다(ADR-070). DB 에 이름 그대로 저장되므로 값을 바꾸면 마이그레이션이 필요하다.
 *
 * <p>언제 만드는지는 {@code docs/backend/notification.md} 의 「알림 종류」 가 갖는다.
 */
public enum NotificationKind {
    /** 승인이 필요한 커넥터 호출이 새 승인 줄을 만들었다. */
    APPROVAL_REQUESTED,
    /** 그 승인 줄이 답을 받지 못해 만료됐다. */
    APPROVAL_EXPIRED,
    /** 예약 작업의 발화가 답을 마쳤다. */
    TASK_SUCCEEDED,
    /** 예약 작업의 발화가 실패했다. */
    TASK_FAILED,
    /** 예약 작업의 발화를 열지 않고 건너뛰었다. */
    TASK_SKIPPED,
    /** 에이전트가 쓸 관리자 등급 도구의 사용 요청이다. */
    TOOLSET_REQUESTED,
    /** 도구 사용 요청의 승인, 거절 또는 만료가 확정됐다. */
    TOOLSET_REQUEST_DECIDED
}
