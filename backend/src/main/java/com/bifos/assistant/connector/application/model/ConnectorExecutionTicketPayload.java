package com.bifos.assistant.connector.application.model;

import java.time.Instant;
import java.util.UUID;

/** 서명할 14개 칸이다. 원문 ticket이나 복호화한 본문을 포함하지 않는다. */
public record ConnectorExecutionTicketPayload(
        int v,
        UUID ticketId,
        UUID actionId,
        Long userId,
        Long agentId,
        Long connectionId,
        Long bindingId,
        String profile,
        String connectorId,
        String tool,
        String argsSha256,
        String scopeSha256,
        Instant issuedAt,
        Instant expiresAt) {}
