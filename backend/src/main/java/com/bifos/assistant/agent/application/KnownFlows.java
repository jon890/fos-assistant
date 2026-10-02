package com.bifos.assistant.agent.application;

/** 에이전트에 적을 수 있는 흐름 이름을 알려 준다. 구현은 흐름 쪽이 갖는다. */
public interface KnownFlows {

    /** 등록된 흐름 이름이면 true 다. null 과 모르는 이름은 false 다. */
    boolean known(String name);
}
