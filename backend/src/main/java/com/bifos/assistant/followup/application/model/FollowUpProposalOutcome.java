package com.bifos.assistant.followup.application.model;

/** 에이전트가 할 일을 제안한 결과다. 뜻은 {@code backend/docs/flow.md} 의 「제안 억제」 가 갖는다. */
public enum FollowUpProposalOutcome {
    /** 새 {@code PROPOSED} 줄을 만들었다. */
    CREATED,
    /** 같은 사용자에게 같은 제목의 열린 줄이 있어 새 줄을 만들지 않았다. */
    DUPLICATE,
    /** 같은 대화에서 30일 안에 거절한 제목이다. */
    DECLINED_BEFORE,
    /** 같은 대화에 받아들이기를 기다리는 제안이 상한만큼 있다. */
    TOO_MANY_PROPOSALS,
    /** 한 실행이 제안할 수 있는 상한에 닿았다. */
    TOO_MANY_IN_RUN
}
