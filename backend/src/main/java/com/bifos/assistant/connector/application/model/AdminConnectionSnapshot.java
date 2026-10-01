package com.bifos.assistant.connector.application.model;

import com.bifos.assistant.connector.domain.type.ConnectionStatus;

/** 관리자가 보는 연결 한 줄이다. 다른 사용자의 칸 값과 비밀 앞부분은 담지 않는다. */
public record AdminConnectionSnapshot(
        String connectorId,
        Long userId,
        String displayName,
        ConnectionStatus status,
        String agentCode,
        boolean restartRequired) {}
