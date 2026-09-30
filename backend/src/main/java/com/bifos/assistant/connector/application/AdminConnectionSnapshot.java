package com.bifos.assistant.connector.application;

import com.bifos.assistant.connector.domain.ConnectionStatus;

public record AdminConnectionSnapshot(Long userId, String displayName, ConnectionStatus status,
        String agentCode, boolean restartRequired) {}
