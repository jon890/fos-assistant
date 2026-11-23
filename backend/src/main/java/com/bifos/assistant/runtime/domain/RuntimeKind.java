package com.bifos.assistant.runtime.domain;

/**
 * 실행을 맡는 Agent Runtime 의 종류다.
 *
 * <p>종류마다 실행 port 의 구현이 하나 있다. 근거는 ADR-20261121 / acp-runtime 에 있다.
 */
public enum RuntimeKind {
    HERMES
}
