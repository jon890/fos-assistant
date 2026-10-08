package com.bifos.assistant.memory.application.model;

import java.util.List;

/**
 * 관리자가 보는 에이전트의 Memory collection 설정이다.
 *
 * @param ownerName 에이전트 주인의 표시 이름이다. 주인이 없으면 null 이다
 * @param collections 그룹의 collection 목록 순서이고, 목록에 없는 받는 collection 은 key 순서로 뒤에 붙는다
 * @param changes 최근 변경 기록 10줄이다. 새것부터다
 */
public record AgentMemorySetting(
        AgentMemoryCountScope countedFor,
        String ownerName,
        List<AgentMemorySettingCollection> collections,
        List<AgentMemorySettingChange> changes) {}
