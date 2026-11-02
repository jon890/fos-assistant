package com.bifos.assistant.people.application.dto;

/** 사용자 정리 단계 하나의 결과다. */
public enum UserPurgeOutcome {
    /** 이 단계를 마쳤다. 다음 단계로 간다. */
    DONE,
    /** 대화 정리가 아직 끝나지 않았다. 다음 차례에 다시 본다. 실패가 아니다. */
    WAITING,
    /** 그 사이 다시 켜졌거나 예약이 취소돼 정리 대상이 아니다. 아무것도 하지 않았다. */
    CANCELLED
}
