package com.bifos.assistant.memory.application.model;

import java.time.Instant;

/**
 * 에이전트가 받는 collection 이 바뀐 기록 한 줄이다.
 *
 * @param changedByName 바꾼 관리자의 표시 이름이다. 그 사용자를 찾지 못하면 null 이다
 */
public record AgentMemorySettingChange(
        String collection, String changeType, boolean allowSensitive, String changedByName, Instant changedAt) {}
