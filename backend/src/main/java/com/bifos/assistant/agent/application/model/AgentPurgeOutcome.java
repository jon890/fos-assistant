package com.bifos.assistant.agent.application.model;

/** 지운 에이전트 하나를 정리한 결과다(ADR-20261009 / agent-purge). */
public enum AgentPurgeOutcome {
    /** 에이전트 행과 딸린 줄을 지웠다. */
    PURGED,
    /** 아직 지우면 안 되는 까닭이 있어 미뤘다. 다음 차례에 다시 본다. */
    WAITING,
    /** 행이 없거나, 지운 에이전트가 아니거나, 지운 지 아직 기한이 지나지 않았다. 아무것도 하지 않았다. */
    GONE
}
