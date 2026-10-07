package com.bifos.assistant.proactive.domain.type;

/** 문제 후보를 받아들였는지다. 판정 순서와 조건은 {@code docs/backend/proactive-check.md} 의 「후보 검사」 가 갖는다. */
public enum ProblemStatus {
    /** 검사를 모두 통과했다. 다음 단계가 읽는다. */
    ACCEPTED,
    /** 검사에 걸려 버렸다. 까닭은 {@link ProblemDropReason} 이 갖는다. */
    DROPPED
}
