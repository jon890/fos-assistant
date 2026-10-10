package com.bifos.assistant.connector.application.model;

import java.time.Instant;
import java.util.UUID;

/** 소비 트랜잭션의 커밋을 마친 뒤 반환하는 결과다. */
public record ConnectorExecutionClaimResult(int v, boolean allowed, UUID ticketId, Instant expiresAt) {}
