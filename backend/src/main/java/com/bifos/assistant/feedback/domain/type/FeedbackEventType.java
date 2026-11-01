package com.bifos.assistant.feedback.domain.type;

/**
 * 판단 피드백 기록의 사건 종류다. 실제 화면과 API 흐름에서 일어나는 것만 둔다. 어느 서비스가 어느 사건을 남기는지는
 * {@code DecisionFeedbackRecorder.record} 의 호출 지점이 갖는다.
 *
 * <p>응답하지 않음(무시)은 사건이 아니다. {@code SURFACED} 뒤에 사용자 반응이 없다는 사실로만 읽고 싫어함으로 적지 않는다.
 */
public enum FeedbackEventType {
    /** 사용자가 볼 수 있는 자리에 제안을 올렸다. 주체는 에이전트나 시스템이다. */
    SURFACED,
    /** 제안을 받아들였다. */
    ACCEPTED,
    /** 제안을 숨겼거나, 받아들인 할 일을 그만뒀다. */
    DISMISSED,
    /** 제안을 미뤘다. */
    POSTPONED,
    /** 제안에서 온 할 일을 고쳤다. */
    EDITED,
    /** 승인 줄을 승인했다. */
    APPROVED,
    /** 제안이나 승인 줄을 거절했다. */
    REJECTED,
    /** 실행이 성공했다. 제안에서 온 할 일을 끝낸 것도 여기다. 주체는 사용자나 시스템이다. */
    EXECUTION_SUCCEEDED,
    /** 실행이 실패했거나 시작하지 못했다. 주체는 시스템이다. */
    EXECUTION_FAILED,
    /**
     * 사용자가 답의 「참고한 기억」 에서 그 기억이 이 답과 관련 없다고 눌렀다. 열쇠는 {@code memory:<번호>} 이고 기억 내용은 바뀌지 않는다.
     * 정비 규칙의 입력 신호로만 읽는다(ADR-20261015 / memory-maintenance).
     */
    MARKED_IRRELEVANT,
    /** 사용자가 답의 「참고한 기억」 에서 그 기억이 틀렸다고 눌렀다. 고치는 것은 사람이 기억 화면에서 한다. 기억 내용은 바뀌지 않는다. */
    MARKED_WRONG,
    /**
     * 정비 작업이 기억 항목의 정비 후보를 만들었다. 주체는 시스템이다. 열쇠는 {@code memory:<번호>}, 동작 종류는 {@code reason_code}, 그때의
     * 판 번호는 {@code subject_version}, 규칙 버전과 신호 값은 {@code detail} 이다. 제안 모드에서는 기억을 바꾸지 않는다.
     */
    MAINTENANCE_PROPOSED,
    /** 사람이 정비 후보를 「맞음」 으로 확인했다. 이 단계에서는 동작을 적용하지 않고 확인 결과만 남긴다. */
    MAINTENANCE_CONFIRMED,
    /** 사람이 정비 후보를 「아님」 으로 확인했다. */
    MAINTENANCE_DENIED,
    /** 지금 화면 응답에 항목이 실렸다. 주체는 시스템이다. */
    SHOWN,
    /** 사용자가 지금 화면의 항목을 열었다. */
    OPENED,
    /** 사용자가 지금 화면의 항목 단추로 동작했다. */
    ACTED,
    /**
     * 사용자가 지금 화면에서 한 카드의 숨김이나 미루기를 되돌렸다. 그 카드의 그 항목은 이 사건 뒤로 제어가 없다.
     * 숨김({@code DISMISSED})과 미루기({@code POSTPONED})는 {@code card_key} 가 있으면 제어 사건이다.
     */
    RESTORED;


    /**
     * 「참고한 기억」 단추 사건과 정비 사건인가. 제안의 흐름이 아니라 답에 대한 반응이거나 정비 규칙의 기록이라 반응 읽기, replay 읽기
     * 모델, 대화 삭제의 열쇠 확장에서 뺀다.
     */
    public boolean marksMemoryUse() {
        return this == MARKED_IRRELEVANT || this == MARKED_WRONG || marksMaintenance();
    }


    /** 정비 사건(후보, 확인)인가. */
    public boolean marksMaintenance() {
        return this == MAINTENANCE_PROPOSED || this == MAINTENANCE_CONFIRMED || this == MAINTENANCE_DENIED;
    }


    /**
     * 지금 화면의 노출, 열기, 동작, 복원 사건인가. 제안의 흐름이 아니라 화면을 쓴 기록이라 replay 읽기 모델에서 뺀다.
     */
    public boolean marksAttentionView() {
        return this == SHOWN || this == OPENED || this == ACTED || this == RESTORED;
    }
}
