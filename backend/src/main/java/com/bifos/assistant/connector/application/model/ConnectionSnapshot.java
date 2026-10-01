package com.bifos.assistant.connector.application.model;

import com.bifos.assistant.connector.domain.type.ConnectionStatus;
import java.time.Instant;
import java.util.Map;

/**
 * 자기 연결 하나의 상태다. 비밀 칸은 앞 8자만 담는다.
 *
 * @param secretPrefixes 비밀 칸의 앞 8자
 * @param values 비밀이 아닌 칸의 값
 */
public record ConnectionSnapshot(
        String connectorId,
        ConnectionStatus status,
        Map<String, String> secretPrefixes,
        Map<String, String> values,
        boolean restartRequired,
        Instant checkedAt,
        String agentCode) {}
