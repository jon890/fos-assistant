package com.bifos.assistant.connector.application;

import com.bifos.assistant.connector.domain.ConnectionStatus;
import java.time.Instant;
import java.util.UUID;

public record ConnectionSnapshot(ConnectionStatus status, String tokenPrefix, UUID familyUuid,
        boolean restartRequired, Instant checkedAt, String agentCode) {}
