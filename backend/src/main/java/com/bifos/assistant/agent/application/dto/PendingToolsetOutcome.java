package com.bifos.assistant.agent.application.dto;

/** 기다리는 기본 도구를 한 번 켜 본 결과다(ADR-20261119 / image-gen-defaults). */
public enum PendingToolsetOutcome {
    /** 켰거나 이미 켜져 있다. 더 기다리지 않는다. */
    ENABLED,
    /** 그 profile 의 실행 공간이 준비되지 않았다. 운영이 정책에 등록하면 다음 차례에 켜진다. */
    SANDBOX_UNAVAILABLE,
    /** 비밀값을 요청하는 올린 스킬이 있어 실행 공간 도구를 켤 수 없다. 스킬을 지우면 다음 차례에 켜진다. */
    SKILL_REQUESTS_SECRETS,
    /** 그룹에 공개했거나 주인이 없어 이 도구를 켤 수 없는 에이전트다. 기다리지 않는다. */
    NOT_ALLOWED
}
