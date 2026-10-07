package com.bifos.assistant.memory.application.model;

/**
 * 기억을 남긴 에이전트를 화면에 어떻게 보일지다.
 *
 * <p>에이전트가 남긴 기억에만 만든다. 사람이 직접 만들거나 가져온 기억은 출처가 없다.
 *
 * @param agentName 남긴 에이전트의 이름. 읽는 사용자가 그 에이전트를 볼 수 있을 때만 싣는다
 * @param agentDeleted 남긴 에이전트가 지워졌다. 이름은 싣지 않는다
 */
public record MemorySource(String agentName, boolean agentDeleted) {

    /** 남긴 에이전트를 알 수 없거나 읽는 사용자가 볼 수 없는 에이전트다. 「에이전트가 남김」 만 보인다. */
    public static final MemorySource UNNAMED = new MemorySource(null, false);
}
