package com.bifos.assistant.followup.application.dto;

/** 먼저 살펴보기의 제안 항목 하나를 저장한 결과다. */
public enum SuggestionOutcome {
    /** 새 {@code PROPOSED} 줄을 만들었다. */
    CREATED,
    /** 같은 실행의 같은 항목이거나 같은 제목의 열린 줄이 있어 새 줄을 만들지 않았다. */
    DUPLICATE,
    /** 같은 대화에서 30일 안에 거절한 제목이다. */
    DECLINED_BEFORE,
    /** 한 실행이 제안할 수 있는 상한에 닿았다. */
    TOO_MANY_IN_RUN,
    /** 제목이나 본문이 계약에 맞지 않거나, 실행 줄이 없거나 대화에 묶이지 않았거나, 본문을 암호화하지 못했다. */
    INVALID
}
