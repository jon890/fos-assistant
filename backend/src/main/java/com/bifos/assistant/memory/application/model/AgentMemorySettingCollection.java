package com.bifos.assistant.memory.application.model;

/**
 * 관리자 화면의 collection 한 줄이다.
 *
 * @param listed 그룹의 collection 목록에 있는지다. 거짓이면 받는 줄만 남은 collection 이고 {@code displayName} 이 key 다
 * @param granted 에이전트가 이 collection 을 받는지다
 * @param allowSensitive 받는 줄의 민감 허용이다. 받지 않으면 거짓이다
 * @param entryCount 셈 대상의 실릴 수 있는 항목 수다. 민감 항목도 센다
 * @param sensitiveEntryCount 그 가운데 민감 항목 수다
 */
public record AgentMemorySettingCollection(
        String key,
        String displayName,
        boolean listed,
        boolean granted,
        boolean allowSensitive,
        long entryCount,
        long sensitiveEntryCount) {}
