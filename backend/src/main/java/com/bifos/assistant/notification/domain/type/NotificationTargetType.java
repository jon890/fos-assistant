package com.bifos.assistant.notification.domain.type;

/** 알림을 누르면 갈 곳의 종류다. DB 에 이름 그대로 저장되므로 값을 바꾸면 마이그레이션이 필요하다. */
public enum NotificationTargetType {
    /** 대화. 공개 식별자로 가리킨다. */
    CONVERSATION,
    /** 예약 작업. 공개 식별자로 가리킨다. */
    TASK,
    /** 관리자 영역의 도구 사용 요청이다. */
    ADMIN_TOOL_REQUEST,
    /** 요청자가 읽는 도구 사용 요청의 결과다. */
    TOOLSET_REQUEST
}
