package com.bifos.assistant.followup.domain.type;

/** 할 일의 상태다. {@code follow_up.status} 가 이 이름을 저장한다. 전이는 {@code docs/features/attention.md} 의 「상태」 가 갖는다. */
public enum FollowUpStatus {
    /** 에이전트가 제안했다. 사람이 받아들이거나 거절한다. */
    PROPOSED,
    /** 챙기는 중이다. */
    OPEN,
    /** 끝났다. */
    DONE,
    /** 그만두었다. */
    DROPPED,
    /** 제안을 거절했다. */
    REJECTED;

    /** 아직 끝나지 않은 상태인가. 열린 줄만 같은 제목의 유일 제약에 걸린다. */
    public boolean open() {
        return this == PROPOSED || this == OPEN;
    }
}
